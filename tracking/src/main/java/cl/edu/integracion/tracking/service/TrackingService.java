package cl.edu.integracion.tracking.service;

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

    public TrackingService(TrackingRepository trackingRepository) {
        this.trackingRepository = trackingRepository;
    }

    @Transactional
    public String generarTracking(String transaccionId) {
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }
        TrackingEnvio existente = trackingRepository.findByTransaccionId(transaccionId).orElse(null);
        if (existente != null) {
            if ("ANULADO".equals(existente.getEstado())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El tracking de esta transacción ya fue anulado");
            }
            return existente.getTrackingNumber();
        }

        String trackingNumber = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        trackingRepository.save(new TrackingEnvio(transaccionId, trackingNumber, "GENERADO"));
        return trackingNumber;
    }

    @Transactional
    public void anularTracking(String transaccionId) {
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }
        trackingRepository.findByTransaccionId(transaccionId).ifPresent(t -> {
            if (!"ANULADO".equals(t.getEstado())) {
                t.setEstado("ANULADO");
                trackingRepository.save(t);
            }
        });
    }
}
