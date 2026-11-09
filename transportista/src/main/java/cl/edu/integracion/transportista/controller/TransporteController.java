package cl.edu.integracion.transportista.controller;

import cl.edu.integracion.transportista.dto.TransporteRequest;
import cl.edu.integracion.transportista.dto.TransporteResponse;
import cl.edu.integracion.transportista.service.TransporteService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/shipping")
public class TransporteController {

    private final TransporteService transporteService;

    public TransporteController(TransporteService transporteService) {
        this.transporteService = transporteService;
    }

    @PostMapping("/schedule")
    public ResponseEntity<TransporteResponse> agendar(@RequestBody TransporteRequest request) {
        try {
            String msg;
            try {
                msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
            } catch (DataIntegrityViolationException e) {
                msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
            }
            return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new TransporteResponse(false, e.getReason(), request.getTransaccionId()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<TransporteResponse> cancelar(@RequestBody TransporteRequest request) {
        try {
            String msg = transporteService.cancelarRetiro(request.getTransaccionId());
            return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new TransporteResponse(false, e.getReason(), request.getTransaccionId()));
        }
    }
}
