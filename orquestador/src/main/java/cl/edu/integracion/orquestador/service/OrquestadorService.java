package cl.edu.integracion.orquestador.service;

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

    @Value("${servicios.inventario.url}")
    private String inventarioUrl;

    @Value("${servicios.transportista.url}")
    private String transportistaUrl;

    @Value("${servicios.tracking.url}")
    private String trackingUrl;

    public OrquestadorService(DespachoRepository despachoRepository, RestTemplate restTemplate) {
        this.despachoRepository = despachoRepository;
        this.restTemplate = restTemplate;
    }

    public DespachoResponse procesarDespacho(DespachoRequest req) {
        validarSolicitud(req);

        // Idempotencia a nivel de Orquestador
        Despacho existente = despachoRepository.findByTransaccionId(req.getTransaccionId()).orElse(null);
        if (existente != null) {
            if (existente.getEstado() == EstadoDespacho.COMPLETADA) {
                return new DespachoResponse(true, "Despacho ya completado (idempotente)",
                        existente.getTransaccionId(), existente.getEstado(), existente.getTrackingNumber());
            }
            if (existente.getEstado() == EstadoDespacho.FALLIDA) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La transacción ya fue procesada y falló: " + existente.getDetalleError());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La transacción se encuentra actualmente en curso");
        }

        // 0. Bitácora inicial: INICIADA
        Despacho despacho = despachoRepository.save(
                new Despacho(req.getTransaccionId(), req.getProductoId(), req.getCantidad(), req.getDireccion()));

        boolean inventarioReservado = false;
        boolean transporteAgendado = false;

        try {
            // PASO 1: Reservar Inventario
            log.info("[SAGA {}] Paso 1: Reservando inventario para producto {}", req.getTransaccionId(), req.getProductoId());
            restTemplate.postForEntity(
                    inventarioUrl + "/api/inventory/reserve",
                    new InventarioPasoRequest(req.getTransaccionId(), req.getProductoId(), req.getCantidad()),
                    String.class
            );
            inventarioReservado = true;
            despacho.setEstado(EstadoDespacho.INVENTARIO_RESERVADO);
            despachoRepository.save(despacho);

            // PASO 2: Agendar Transportista
            log.info("[SAGA {}] Paso 2: Agendando retiro en transportista", req.getTransaccionId());
            restTemplate.postForEntity(
                    transportistaUrl + "/api/shipping/schedule",
                    new TransportePasoRequest(req.getTransaccionId(), req.getDireccion()),
                    String.class
            );
            transporteAgendado = true;
            despacho.setEstado(EstadoDespacho.TRANSPORTE_AGENDADO);
            despachoRepository.save(despacho);

            // PASO 3: Generar Tracking
            log.info("[SAGA {}] Paso 3: Generando código de tracking", req.getTransaccionId());
            TrackingPasoResponse trackingResp = restTemplate.postForObject(
                    trackingUrl + "/api/tracking/generate",
                    new TrackingPasoRequest(req.getTransaccionId()),
                    TrackingPasoResponse.class
            );

            if (trackingResp == null || !trackingResp.isSuccess() || trackingResp.getTrackingNumber() == null) {
                throw new RestClientException("Respuesta inválida desde servicio de tracking");
            }

            // ÉXITO TOTAL DE LA SAGA
            despacho.setTrackingNumber(trackingResp.getTrackingNumber());
            despacho.setEstado(EstadoDespacho.COMPLETADA);
            despachoRepository.save(despacho);
            log.info("[SAGA {}] COMPLETADA con tracking {}", req.getTransaccionId(), trackingResp.getTrackingNumber());

            return new DespachoResponse(true, "Despacho procesado con éxito",
                    despacho.getTransaccionId(), despacho.getEstado(), despacho.getTrackingNumber());

        } catch (Exception ex) {
            String motivo = extraerMensajeError(ex);
            log.error("[SAGA {}] Fallo detectado: {}. Iniciando compensación inversa...", req.getTransaccionId(), motivo);

            despacho.setEstado(EstadoDespacho.COMPENSANDO);
            despacho.setDetalleError(motivo);
            despachoRepository.save(despacho);

            // COMPENSACIÓN EN ORDEN INVERSO
            if (transporteAgendado) {
                compensarTransporte(req.getTransaccionId());
            }
            if (inventarioReservado) {
                compensarInventario(req.getTransaccionId());
            }

            despacho.setEstado(EstadoDespacho.FALLIDA);
            despachoRepository.save(despacho);

            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Despacho abortado y compensado. Motivo: " + motivo);
        }
    }

    public DespachoResponse consultarDespacho(String transaccionId) {
        Despacho despacho = despachoRepository.findByTransaccionId(transaccionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No existe despacho para transaccionId: " + transaccionId));

        boolean ok = despacho.getEstado() == EstadoDespacho.COMPLETADA;
        String msg = ok ? "Despacho completado" : "Estado actual: " + despacho.getEstado();

        return new DespachoResponse(ok, msg, despacho.getTransaccionId(),
                despacho.getEstado(), despacho.getTrackingNumber());
    }

    private void compensarTransporte(String transaccionId) {
        try {
            log.warn("[SAGA {}] Compensando Paso 2 -> Cancelando transporte", transaccionId);
            restTemplate.postForEntity(
                    transportistaUrl + "/api/shipping/cancel",
                    new TransportePasoRequest(transaccionId, null),
                    String.class
            );
        } catch (Exception e) {
            log.error("[SAGA {}] Error crítico al compensar transporte: {}", transaccionId, e.getMessage());
        }
    }

    private void compensarInventario(String transaccionId) {
        try {
            log.warn("[SAGA {}] Compensando Paso 1 -> Liberando inventario", transaccionId);
            restTemplate.postForEntity(
                    inventarioUrl + "/api/inventory/release",
                    new InventarioPasoRequest(transaccionId, null, null),
                    String.class
            );
        } catch (Exception e) {
            log.error("[SAGA {}] Error crítico al compensar inventario: {}", transaccionId, e.getMessage());
        }
    }

    private void validarSolicitud(DespachoRequest req) {
        if (req == null
                || vacio(req.getTransaccionId())
                || vacio(req.getProductoId())
                || req.getCantidad() == null || req.getCantidad() <= 0
                || vacio(req.getDireccion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "transaccionId, productoId, cantidad (>0) y direccion son obligatorios");
        }
    }

    private String extraerMensajeError(Exception ex) {
        if (ex instanceof HttpStatusCodeException httpEx) {
            return "HTTP " + httpEx.getStatusCode().value() + " - " + httpEx.getResponseBodyAsString();
        }
        return ex.getMessage() != null ? ex.getMessage() : "Error de comunicación desconocido";
    }

    private boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
