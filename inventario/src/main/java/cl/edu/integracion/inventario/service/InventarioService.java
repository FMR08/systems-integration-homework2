package cl.edu.integracion.inventario.service;

import cl.edu.integracion.inventario.model.Inventario;
import cl.edu.integracion.inventario.model.Reserva;
import cl.edu.integracion.inventario.repository.InventarioRepository;
import cl.edu.integracion.inventario.repository.ReservaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventarioService {

    private final InventarioRepository inventarioRepository;
    private final ReservaRepository reservaRepository;

    public InventarioService(InventarioRepository inventarioRepository, ReservaRepository reservaRepository) {
        this.inventarioRepository = inventarioRepository;
        this.reservaRepository = reservaRepository;
    }

    @Transactional
    public String reservarStock(String transaccionId, String productoId, Integer cantidad) {
        if (vacio(transaccionId) || vacio(productoId) || cantidad == null || cantidad <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "transaccionId, productoId y cantidad (> 0) son obligatorios");
        }
        Inventario inv = inventarioRepository.findByProductoId(productoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Producto inexistente: " + productoId));

        Reserva previa = reservaRepository.findByTransaccionId(transaccionId).orElse(null);
        if (previa != null) {
            if ("RESERVADA".equals(previa.getEstado())) {
                return "Reserva ya existente (idempotente)";
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La transacción ya fue liberada");
        }

        if (inv.getStockDisponible() < cantidad) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock insuficiente");
        }
        inv.setStockDisponible(inv.getStockDisponible() - cantidad);
        inv.setStockReservado(inv.getStockReservado() + cantidad);
        inventarioRepository.save(inv);
        reservaRepository.save(new Reserva(transaccionId, productoId, cantidad, "RESERVADA"));
        return "Stock reservado exitosamente";
    }

    @Transactional
    public String liberarStock(String transaccionId) {
        if (vacio(transaccionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }
        Reserva reserva = reservaRepository.findByTransaccionId(transaccionId).orElse(null);
        if (reserva == null) {
            return "Sin reserva para esta transacción: nada que liberar";
        }
        Inventario inv = inventarioRepository.findByProductoId(reserva.getProductoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto inexistente"));
        reservaRepository.findByTransaccionId(transaccionId).ifPresent(r -> {
            if ("RESERVADA".equals(r.getEstado())) {
                inv.setStockDisponible(inv.getStockDisponible() + r.getCantidad());
                inv.setStockReservado(Math.max(0, inv.getStockReservado() - r.getCantidad()));
                r.setEstado("LIBERADA");
                inventarioRepository.save(inv);
                reservaRepository.save(r);
            }
        });
        return "Stock liberado (compensación aplicada)";
    }

    private boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
