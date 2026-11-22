package cl.edu.integracion.inventario.controller;

import cl.edu.integracion.inventario.dto.ReservaDetalleResponse;
import cl.edu.integracion.inventario.dto.ReservaRequest;
import cl.edu.integracion.inventario.dto.ReservaResponse;
import cl.edu.integracion.inventario.model.Reserva;
import cl.edu.integracion.inventario.service.InventarioService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventarioController {
    private final InventarioService inventarioService;

    public InventarioController(final InventarioService inventarioService) {
        this.inventarioService = inventarioService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<ReservaResponse> reservar(@RequestBody final ReservaRequest request) {
        String msg;
        try {
            msg = inventarioService.reservarStock(
                request.getTransaccionId(),
                request.getProductoId(),
                request.getCantidad()
            );
        } catch (final DataIntegrityViolationException e) {
            msg = inventarioService.reservarStock(
                request.getTransaccionId(),
                request.getProductoId(),
                request.getCantidad()
            );
        }
        return ResponseEntity.ok(new ReservaResponse(true, msg, request.getTransaccionId()));
    }

    @PostMapping("/release")
    public ResponseEntity<ReservaResponse> liberar(@RequestBody final ReservaRequest request) {
        String msg;
        try {
            msg = inventarioService.liberarStock(
                request.getTransaccionId(),
                request.getProductoId(),
                request.getCantidad()
            );
        } catch (final DataIntegrityViolationException e) {
            msg = inventarioService.liberarStock(
                request.getTransaccionId(),
                request.getProductoId(),
                request.getCantidad()
            );
        }
        return ResponseEntity.ok(new ReservaResponse(true, msg, request.getTransaccionId()));
    }

    @GetMapping("/reservations/{transaccionId}")
    public ResponseEntity<ReservaDetalleResponse> consultar(@PathVariable final String transaccionId) {
        final Reserva r = inventarioService.consultarReserva(transaccionId);
        return ResponseEntity.ok(new ReservaDetalleResponse(
            r.getTransaccionId(),
            r.getProductoId(),
            r.getCantidad(),
            r.getEstado()
        ));
    }
}
