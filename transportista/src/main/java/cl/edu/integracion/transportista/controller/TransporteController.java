package cl.edu.integracion.transportista.controller;

import cl.edu.integracion.transportista.chaos.ChaosState;
import cl.edu.integracion.transportista.dto.TransporteRequest;
import cl.edu.integracion.transportista.dto.TransporteResponse;
import cl.edu.integracion.transportista.service.TransporteService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shipping")
public class TransporteController {

    private final TransporteService transporteService;
    private final ChaosState chaos;

    public TransporteController(TransporteService transporteService, ChaosState chaos) {
        this.transporteService = transporteService;
        this.chaos = chaos;
    }

    @PostMapping("/schedule")
    public ResponseEntity<TransporteResponse> agendar(@RequestBody TransporteRequest request) {
        chaos.aplicar(); // falla inducida solo en schedule; cancel queda sano para poder compensar
        String msg;
        try {
            msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
        } catch (DataIntegrityViolationException e) {
            msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
        }
        return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<TransporteResponse> cancelar(@RequestBody TransporteRequest request) {
        String msg = transporteService.cancelarRetiro(request.getTransaccionId());
        return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
    }
}