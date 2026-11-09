package cl.edu.integracion.inventario.controller;

import cl.edu.integracion.inventario.dto.ReservaRequest;
import cl.edu.integracion.inventario.dto.ReservaResponse;
import cl.edu.integracion.inventario.service.InventarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventarioController {

    private final InventarioService inventarioService;

    public InventarioController(InventarioService inventarioService) {
        this.inventarioService = inventarioService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservaResponse> reservar(@RequestBody ReservaRequest request) {
        try {
            String msg = inventarioService.reservarStock(
                    request.getTransaccionId(), request.getProductoId(), request.getCantidad());
            return ResponseEntity.ok(new ReservaResponse(true, msg, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new ReservaResponse(false, e.getReason(), request.getTransaccionId()));
        }
    }

    @PostMapping("/release")
    public ResponseEntity<ReservaResponse> liberar(@RequestBody ReservaRequest request) {
        try {
            String msg = inventarioService.liberarStock(request.getTransaccionId());
            return ResponseEntity.ok(new ReservaResponse(true, msg, request.getTransaccionId()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new ReservaResponse(false, e.getReason(), request.getTransaccionId()));
        }
    }
}
