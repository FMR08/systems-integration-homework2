package cl.edu.integracion.orquestador.controller;

import cl.edu.integracion.orquestador.dto.DespachoRequest;
import cl.edu.integracion.orquestador.dto.DespachoResponse;
import cl.edu.integracion.orquestador.service.OrquestadorService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/despachos")
public class DespachoController {
    private final OrquestadorService orquestadorService;

    public DespachoController(final OrquestadorService orquestadorService) {
        this.orquestadorService = orquestadorService;
    }

    @PostMapping
    public ResponseEntity<DespachoResponse> crearDespacho(@RequestBody final DespachoRequest request) {
        try {
            return ResponseEntity.ok(orquestadorService.procesarDespacho(request));
        } catch (final DataIntegrityViolationException e) {
            return ResponseEntity.ok(orquestadorService.consultarDespacho(request.getTransaccionId()));
        }
    }

    @GetMapping("/{transaccionId}")
    public ResponseEntity<DespachoResponse> obtenerDespacho(@PathVariable final String transaccionId) {
        return ResponseEntity.ok(orquestadorService.consultarDespacho(transaccionId));
    }
}
