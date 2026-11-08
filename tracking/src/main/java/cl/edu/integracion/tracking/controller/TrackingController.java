package cl.edu.integracion.tracking.controller;

import cl.edu.integracion.tracking.dto.TrackingRequest;
import cl.edu.integracion.tracking.dto.TrackingResponse;
import cl.edu.integracion.tracking.service.TrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            String trackingNum = trackingService.generarTracking(request.getTransaccionId());
            return ResponseEntity.ok(new TrackingResponse(true, "Tracking generado con éxito", trackingNum, request.getTransaccionId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new TrackingResponse(false, e.getMessage(), null, request.getTransaccionId()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<TrackingResponse> anular(@RequestBody TrackingRequest request) {
        try {
            trackingService.anularTracking(request.getTransaccionId());
            return ResponseEntity.ok(new TrackingResponse(true, "Tracking anulado (compensación aplicada)", null, request.getTransaccionId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new TrackingResponse(false, e.getMessage(), null, request.getTransaccionId()));
        }
    }
}
