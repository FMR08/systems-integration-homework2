package cl.edu.integracion.tracking.service;

import cl.edu.integracion.tracking.chaos.ChaosState;
import cl.edu.integracion.tracking.model.EstadoTracking;
import cl.edu.integracion.tracking.model.TrackingEnvio;
import cl.edu.integracion.tracking.repository.TrackingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class TrackingService {

    private final TrackingRepository trackingRepository;
    private final ChaosState chaos;

    public TrackingService(TrackingRepository trackingRepository, ChaosState chaos) {
        this.trackingRepository = trackingRepository;
        this.chaos = chaos;
    }

    @Transactional
    public String generarTracking(String transaccionId) {
        if (vacio(transaccionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }

        TrackingEnvio existente = trackingRepository.findByTransaccionId(transaccionId).orElse(null);
        if (existente != null) {
            if (existente.getEstado() == EstadoTracking.ANULADO) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El tracking de esta transacción ya fue anulado");
            }
            return existente.getTrackingNumber();
        }

        if (chaos.isFallarGenerar()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Falla inducida (chaos) en tracking");
        }

        String trackingNumber = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        trackingRepository.save(new TrackingEnvio(transaccionId, trackingNumber, EstadoTracking.GENERADO));
        return trackingNumber;
    }

    @Transactional
    public String anularTracking(String transaccionId) {
        if (vacio(transaccionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }

        TrackingEnvio tracking = trackingRepository.findByTransaccionId(transaccionId).orElse(null);
        if (tracking == null) {
            return "Sin tracking para esta transacción: nada que anular";
        }

        if (tracking.getEstado() == EstadoTracking.ANULADO) {
            return "Tracking ya anulado previamente (idempotente)";
        }

        tracking.setEstado(EstadoTracking.ANULADO);
        trackingRepository.save(tracking);
        return "Tracking anulado (compensación aplicada)";
    }

    private boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}