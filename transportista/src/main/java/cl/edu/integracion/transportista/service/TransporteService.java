package cl.edu.integracion.transportista.service;

import cl.edu.integracion.transportista.model.EstadoRetiro;
import cl.edu.integracion.transportista.model.RetiroTransporte;
import cl.edu.integracion.transportista.repository.TransporteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransporteService {
    private final TransporteRepository transporteRepository;

    public TransporteService(final TransporteRepository transporteRepository) {
        this.transporteRepository = transporteRepository;
    }

    @Transactional
    public String agendarRetiro(final String transaccionId, final String direccion) {
        if (vacio(transaccionId) || vacio(direccion)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "transaccionId y direccion son obligatorios"
            );
        }

        final RetiroTransporte existente = transporteRepository.findByTransaccionId(transaccionId).orElse(null);
        if (existente != null) {
            if (existente.getEstado() == EstadoRetiro.CANCELADO) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El retiro de esta transacción ya fue cancelado"
                );
            }
            return "Retiro ya agendado (idempotente)";
        }

        transporteRepository.save(new RetiroTransporte(transaccionId, direccion, EstadoRetiro.AGENDADO));
        return "Retiro agendado con éxito";
    }

    @Transactional
    public String cancelarRetiro(final String transaccionId) {
        if (vacio(transaccionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }

        final RetiroTransporte retiro = transporteRepository.findByTransaccionId(transaccionId).orElse(null);
        if (retiro == null) {
            transporteRepository.save(new RetiroTransporte(transaccionId, "N/A", EstadoRetiro.CANCELADO));
            return "Retiro marcado como cancelado de forma preventiva";
        }

        if (retiro.getEstado() == EstadoRetiro.CANCELADO) {
            return "Retiro ya cancelado previamente (idempotente)";
        }

        retiro.setEstado(EstadoRetiro.CANCELADO);
        transporteRepository.save(retiro);
        return "Retiro cancelado (compensación aplicada)";
    }

    private boolean vacio(final String s) {
        return s == null || s.isBlank();
    }

    @Transactional(readOnly = true)
    public RetiroTransporte consultarRetiro(final String transaccionId) {
        return transporteRepository.findByTransaccionId(transaccionId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No existe retiro para transaccionId: " + transaccionId
            ));
    }
}
