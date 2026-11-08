package cl.edu.integracion.inventario.controller;

import cl.edu.integracion.inventario.dto.ReservaRequest;
import cl.edu.integracion.inventario.dto.ReservaResponse;
import cl.edu.integracion.inventario.service.InventarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventarioController {

    private final InventarioService inventarioService;

    public InventarioController(InventarioService inventarioService) {
        this.inventarioService = inventarioService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservaResponse> reservar(@RequestBody ReservaRequest request) {
        boolean exito = inventarioService.reservarStock(request.getProductoId(), request.getCantidad());
        if (exito) {
            return ResponseEntity.ok(new ReservaResponse(true, "Stock reservado exitosamente", request.getTransaccionId()));
        } else {
            return ResponseEntity.badRequest().body(new ReservaResponse(false, "Stock insuficiente", request.getTransaccionId()));
        }
    }

    @PostMapping("/release")
    public ResponseEntity<ReservaResponse> liberar(@RequestBody ReservaRequest request) {
        try {
            inventarioService.liberarStock(request.getProductoId(), request.getCantidad());
            return ResponseEntity.ok(new ReservaResponse(true, "Stock liberado (compensación aplicada)", request.getTransaccionId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ReservaResponse(false, e.getMessage(), request.getTransaccionId()));
        }
    }
}
