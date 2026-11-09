package cl.edu.integracion.tracking.controller;

import cl.edu.integracion.tracking.dto.TrackingRequest;
import cl.edu.integracion.tracking.dto.TrackingResponse;
import cl.edu.integracion.tracking.service.TrackingService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tracking")
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping("/generate")
    public ResponseEntity<TrackingResponse> generar(@RequestBody TrackingRequest request) {
        try {
            String trackingNum;
            try {
                trackingNum = trackingService.generarTracking(request.getTransaccionId());
            } catch (DataIntegrityViolationException e) {
                trackingNum = trackingService.generarTracking(request.getTransaccionId());
            }
            return ResponseEntity.ok(new TrackingResponse(true, "Tracking generado con éxito", trackingNum, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new TrackingResponse(false, e.getReason(), null, request.getTransaccionId()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<TrackingResponse> anular(@RequestBody TrackingRequest request) {
        try {
            trackingService.anularTracking(request.getTransaccionId());
            return ResponseEntity.ok(new TrackingResponse(true, "Tracking anulado (compensación aplicada)", null, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new TrackingResponse(false, e.getReason(), null, request.getTransaccionId()));
        }
    }
}
