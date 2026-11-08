package cl.edu.integracion.transportista.controller;

import cl.edu.integracion.transportista.dto.TransporteRequest;
import cl.edu.integracion.transportista.dto.TransporteResponse;
import cl.edu.integracion.transportista.service.TransporteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
            return ResponseEntity.ok(new TransporteResponse(true, "Retiro agendado con éxito", request.getTransaccionId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new TransporteResponse(false, e.getMessage(), request.getTransaccionId()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<TransporteResponse> cancelar(@RequestBody TransporteRequest request) {
        try {
            transporteService.cancelarRetiro(request.getTransaccionId());
            return ResponseEntity.ok(new TransporteResponse(true, "Retiro cancelado (compensación aplicada)", request.getTransaccionId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new TransporteResponse(false, e.getMessage(), request.getTransaccionId()));
        }
    }
}
