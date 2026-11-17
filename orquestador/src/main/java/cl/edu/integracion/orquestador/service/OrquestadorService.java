package cl.edu.integracion.orquestador.service;

import cl.edu.integracion.orquestador.client.TransportistaClient;
import cl.edu.integracion.orquestador.client.TransportistaNoDisponibleException;
import cl.edu.integracion.orquestador.dto.*;
import cl.edu.integracion.orquestador.model.Despacho;
import cl.edu.integracion.orquestador.model.EstadoDespacho;
import cl.edu.integracion.orquestador.repository.DespachoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrquestadorService {

    private static final Logger log = LoggerFactory.getLogger(OrquestadorService.class);

    private final DespachoRepository despachoRepository;
    private final RestTemplate restTemplate;
    private final TransportistaClient transportistaClient;

    @Value("${servicios.inventario.url}")
    private String inventarioUrl;

    @Value("${servicios.tracking.url}")
    private String trackingUrl;

    public OrquestadorService(
            DespachoRepository despachoRepository,
            RestTemplate restTemplate,
            TransportistaClient transportistaClient) {
        this.despachoRepository = despachoRepository;
        this.restTemplate = restTemplate;
        this.transportistaClient = transportistaClient;
    }

    public DespachoResponse procesarDespacho(DespachoRequest req) {

        validarSolicitud(req);

        // Idempotencia a nivel de Orquestador
        Despacho existente = despachoRepository
                .findByTransaccionId(req.getTransaccionId())
                .orElse(null);

        if (existente != null) {

            if (existente.getEstado() == EstadoDespacho.COMPLETADA) {
                return new DespachoResponse(
                        true,
                        "Despacho ya completado (idempotente)",
                        existente.getTransaccionId(),
                        existente.getEstado(),
                        existente.getTrackingNumber()
                );
            }

            if (existente.getEstado() == EstadoDespacho.FALLIDA
                    || existente.getEstado() == EstadoDespacho.COMPENSADA) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La transacción ya fue procesada: "
                                + existente.getEstado()
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La transacción se encuentra actualmente en curso"
            );
        }

        // 0. Registrar inicio de Saga
        Despacho despacho = despachoRepository.save(
                new Despacho(
                        req.getTransaccionId(),
                        req.getProductoId(),
                        req.getCantidad(),
                        req.getDireccion()
                )
        );

        boolean inventarioReservado = false;
        boolean transporteAgendado = false;
        boolean trackingGenerado = false;
        try {

            // PASO 1: Reservar Inventario
            log.info("[SAGA {}] Paso 1: Reservando inventario",
                    req.getTransaccionId());
            inventarioReservado = true;

            restTemplate.postForEntity(
                    inventarioUrl + "/api/inventory/reserve",
                    new InventarioPasoRequest(
                            req.getTransaccionId(),
                            req.getProductoId(),
                            req.getCantidad()
                    ),
                    String.class
            );

            despacho.setEstado(EstadoDespacho.INVENTARIO_RESERVADO);
            despachoRepository.save(despacho);

            // PASO 2: Agendar Transporte (Circuit Breaker + Bulkhead dentro del cliente)
            transporteAgendado = true;
            log.info("[SAGA {}] Paso 2: Agendando transporte",
                    req.getTransaccionId());

            transportistaClient.agendar(
                    new TransportePasoRequest(
                            req.getTransaccionId(),
                            req.getDireccion()
                    )
            );

            despacho.setEstado(EstadoDespacho.TRANSPORTE_AGENDADO);
            despachoRepository.save(despacho);

            // PASO 3: Generar Tracking
            trackingGenerado = true;
            log.info("[SAGA {}] Paso 3: Generando tracking",
                    req.getTransaccionId());

            TrackingPasoResponse trackingResp = restTemplate.postForObject(
                    trackingUrl + "/api/tracking/generate",
                    new TrackingPasoRequest(req.getTransaccionId()),
                    TrackingPasoResponse.class
            );

            if (trackingResp == null
                    || !trackingResp.isSuccess()
                    || trackingResp.getTrackingNumber() == null) {

                throw new RestClientException(
                        "Respuesta inválida desde servicio de tracking"
                );
            }

            despacho.setTrackingNumber(trackingResp.getTrackingNumber());
            despacho.setEstado(EstadoDespacho.TRACKING_GENERADO);
            despachoRepository.save(despacho);

            log.info("[SAGA {}] Tracking generado: {}",
                    req.getTransaccionId(),
                    trackingResp.getTrackingNumber());

            // ÉXITO TOTAL DE LA SAGA
            despacho.setEstado(EstadoDespacho.COMPLETADA);
            despachoRepository.save(despacho);

            log.info("[SAGA {}] COMPLETADA",
                    req.getTransaccionId());

            return new DespachoResponse(
                    true,
                    "Despacho procesado con éxito",
                    despacho.getTransaccionId(),
                    despacho.getEstado(),
                    despacho.getTrackingNumber()
            );

        } catch (Exception ex) {

            String motivo = extraerMensajeError(ex);

            log.error("[SAGA {}] Fallo detectado: {}",
                    req.getTransaccionId(), motivo);

            // Iniciar compensación
            despacho.setEstado(EstadoDespacho.COMPENSANDO);
            despacho.setDetalleError(motivo);
            despachoRepository.save(despacho);

            boolean compensacionExitosa = true;
            StringBuilder erroresCompensacion = new StringBuilder();

            // COMPENSACIONES EN ORDEN INVERSO

            // COMPENSACIÓN 3: Tracking
            if (trackingGenerado) {
                boolean resultado = compensarTracking(req.getTransaccionId());

                if (!resultado) {
                    compensacionExitosa = false;
                    erroresCompensacion.append("Falló compensación de Tracking. ");
                }
            }

            // COMPENSACIÓN 2: Transporte
            if (transporteAgendado) {
                boolean resultado = compensarTransporte(req.getTransaccionId());

                if (!resultado) {
                    compensacionExitosa = false;
                    erroresCompensacion.append("Falló compensación de Transporte. ");
                }
            }

            // COMPENSACIÓN 1: Inventario
            if (inventarioReservado) {
                boolean resultado = compensarInventario(req.getTransaccionId());

                if (!resultado) {
                    compensacionExitosa = false;
                    erroresCompensacion.append("Falló compensación de Inventario. ");
                }
            }

            // ESTADO FINAL DE LA SAGA
            if (compensacionExitosa) {

                despacho.setEstado(EstadoDespacho.COMPENSADA);

                log.info("[SAGA {}] COMPENSADA correctamente",
                        req.getTransaccionId());

            } else {

                despacho.setEstado(EstadoDespacho.FALLIDA);

                despacho.setDetalleError(
                        motivo + " | Errores de compensación: "
                                + erroresCompensacion
                );

                log.error("[SAGA {}] FALLIDA: compensación incompleta",
                        req.getTransaccionId());
            }

            despachoRepository.save(despacho);

            HttpStatus status = (ex instanceof TransportistaNoDisponibleException)
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY;

            throw new ResponseStatusException(
                    status,
                    compensacionExitosa
                            ? "Despacho abortado y compensado. Motivo: " + motivo
                            : "Despacho abortado, pero la compensación fue incompleta. "
                                    + erroresCompensacion
            );
        }
    }

    public DespachoResponse consultarDespacho(String transaccionId) {

        Despacho despacho = despachoRepository
                .findByTransaccionId(transaccionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No existe despacho para transaccionId: " + transaccionId
                ));

        boolean ok = despacho.getEstado() == EstadoDespacho.COMPLETADA;

        String msg = ok
                ? "Despacho completado"
                : "Estado actual: " + despacho.getEstado();

        return new DespachoResponse(
                ok,
                msg,
                despacho.getTransaccionId(),
                despacho.getEstado(),
                despacho.getTrackingNumber()
        );
    }

    // COMPENSACIÓN 3: Tracking
    private boolean compensarTracking(String transaccionId) {

        try {
            log.warn("[SAGA {}] Compensando Paso 3: Cancelando tracking",
                    transaccionId);

            restTemplate.postForEntity(
                    trackingUrl + "/api/tracking/cancel",
                    new TrackingPasoRequest(transaccionId),
                    String.class
            );

            return true;

        } catch (Exception e) {
            log.error("[SAGA {}] Error al compensar tracking: {}",
                    transaccionId, e.getMessage());

            return false;
        }
    }

    // COMPENSACIÓN 2: Transporte (fuera del Circuit Breaker, con retry y backoff)
    private boolean compensarTransporte(String transaccionId) {

        try {
            log.warn("[SAGA {}] Compensando Paso 2: Cancelando transporte",
                    transaccionId);

            transportistaClient.cancelar(transaccionId);

            return true;

        } catch (Exception e) {
            log.error("[SAGA {}] Error al compensar transporte: {}",
                    transaccionId, e.getMessage());

            return false;
        }
    }

    // COMPENSACIÓN 1: Inventario
    private boolean compensarInventario(String transaccionId) {

        try {
            log.warn("[SAGA {}] Compensando Paso 1: Liberando inventario",
                    transaccionId);

            restTemplate.postForEntity(
                    inventarioUrl + "/api/inventory/release",
                    new InventarioPasoRequest(transaccionId, null, null),
                    String.class
            );

            return true;

        } catch (Exception e) {
            log.error("[SAGA {}] Error al compensar inventario: {}",
                    transaccionId, e.getMessage());

            return false;
        }
    }

    private void validarSolicitud(DespachoRequest req) {

        if (req == null
                || vacio(req.getTransaccionId())
                || vacio(req.getProductoId())
                || req.getCantidad() == null
                || req.getCantidad() <= 0
                || vacio(req.getDireccion())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "transaccionId, productoId, cantidad (>0) y direccion son obligatorios"
            );
        }
    }

    private String extraerMensajeError(Exception ex) {

        if (ex instanceof HttpStatusCodeException httpEx) {
            return "HTTP " + httpEx.getStatusCode().value()
                    + " - " + httpEx.getResponseBodyAsString();
        }

        return ex.getMessage() != null
                ? ex.getMessage()
                : "Error de comunicación desconocido";
    }

    private boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}