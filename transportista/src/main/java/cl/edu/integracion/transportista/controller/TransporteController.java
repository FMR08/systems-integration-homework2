package cl.edu.integracion.transportista.controller;

import cl.edu.integracion.transportista.chaos.ChaosState;
import cl.edu.integracion.transportista.dto.RetiroDetalleResponse;
import cl.edu.integracion.transportista.dto.TransporteRequest;
import cl.edu.integracion.transportista.dto.TransporteResponse;
import cl.edu.integracion.transportista.model.RetiroTransporte;
import cl.edu.integracion.transportista.service.TransporteService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shipping")
public class TransporteController {
    private final TransporteService transporteService;
    private final ChaosState chaos;

    public TransporteController(final TransporteService transporteService, final ChaosState chaos) {
        this.transporteService = transporteService;
        this.chaos = chaos;
    }

    @PostMapping("/schedule")
    public ResponseEntity<TransporteResponse> agendar(@RequestBody final TransporteRequest request) {
        chaos.aplicar();
        String msg;
        try {
            msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
        } catch (final DataIntegrityViolationException e) {
            msg = transporteService.agendarRetiro(request.getTransaccionId(), request.getDireccion());
        }
        return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<TransporteResponse> cancelar(@RequestBody final TransporteRequest request) {
        String msg;
        try {
            msg = transporteService.cancelarRetiro(request.getTransaccionId());
        } catch (final DataIntegrityViolationException e) {
            msg = transporteService.cancelarRetiro(request.getTransaccionId());
        }
        return ResponseEntity.ok(new TransporteResponse(true, msg, request.getTransaccionId()));
    }

    /**
     * Lectura sujeta al mismo caos que /schedule: el transportista es la dependencia frágil.
     */
    @GetMapping("/pickups/{transaccionId}")
    public ResponseEntity<RetiroDetalleResponse> consultar(@PathVariable final String transaccionId) {
        chaos.aplicar();
        final RetiroTransporte r = transporteService.consultarRetiro(transaccionId);
        return ResponseEntity.ok(new RetiroDetalleResponse(r.getTransaccionId(), r.getEstado(), r.getDireccion()));
    }
}
