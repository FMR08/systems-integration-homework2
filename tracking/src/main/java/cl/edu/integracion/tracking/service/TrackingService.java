package cl.edu.integracion.tracking.service;

import cl.edu.integracion.tracking.model.TrackingEnvio;
import cl.edu.integracion.tracking.repository.TrackingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TrackingService {

    private final TrackingRepository trackingRepository;

    public TrackingService(TrackingRepository trackingRepository) {
        this.trackingRepository = trackingRepository;
    }

    @Transactional
    public String generarTracking(String transaccionId) {
        TrackingEnvio existente = trackingRepository.findByTransaccionId(transaccionId).orElse(null);
        if (existente != null) {
            return existente.getTrackingNumber();
        }

        String trackingNumber = "TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TrackingEnvio tracking = new TrackingEnvio(transaccionId, trackingNumber, "GENERADO");
        trackingRepository.save(tracking);
        return trackingNumber;
    }

    @Transactional
    public void anularTracking(String transaccionId) {
        TrackingEnvio tracking = trackingRepository.findByTransaccionId(transaccionId)
                .orElseThrow(() -> new RuntimeException("Tracking no encontrado para anular"));

        tracking.setEstado("ANULADO");
        trackingRepository.save(tracking);
    }
}
