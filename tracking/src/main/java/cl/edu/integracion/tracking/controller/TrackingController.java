package cl.edu.integracion.tracking.controller;

import cl.edu.integracion.tracking.dto.TrackingDetalleResponse;
import cl.edu.integracion.tracking.dto.TrackingRequest;
import cl.edu.integracion.tracking.dto.TrackingResponse;
import cl.edu.integracion.tracking.model.TrackingEnvio;
import cl.edu.integracion.tracking.service.TrackingService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tracking")
public class TrackingController {
    private final TrackingService trackingService;

    public TrackingController(final TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping("/generate")
    public ResponseEntity<TrackingResponse> generar(@RequestBody final TrackingRequest request) {
        String trackingNum;
        try {
            trackingNum = trackingService.generarTracking(request.getTransaccionId());
        } catch (final DataIntegrityViolationException e) {
            trackingNum = trackingService.generarTracking(request.getTransaccionId());
        }
        return ResponseEntity.ok(new TrackingResponse(
            true,
            "Tracking generado con éxito",
            trackingNum,
            request.getTransaccionId()
        ));
    }

    @PostMapping("/cancel")
    public ResponseEntity<TrackingResponse> anular(@RequestBody final TrackingRequest request) {
        String msg;
        try {
            msg = trackingService.anularTracking(request.getTransaccionId());
        } catch (final DataIntegrityViolationException e) {
            msg = trackingService.anularTracking(request.getTransaccionId());
        }
        return ResponseEntity.ok(new TrackingResponse(true, msg, null, request.getTransaccionId()));
    }

    @GetMapping("/by-transaction/{transaccionId}")
    public ResponseEntity<TrackingDetalleResponse> consultar(@PathVariable final String transaccionId) {
        final TrackingEnvio t = trackingService.consultarTracking(transaccionId);
        return ResponseEntity.ok(new TrackingDetalleResponse(
            t.getTransaccionId(),
            t.getTrackingNumber(),
            t.getEstado()
        ));
    }
}
