package cl.edu.integracion.inventario.service;

import cl.edu.integracion.inventario.model.Inventario;
import cl.edu.integracion.inventario.repository.InventarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventarioService {

    private final InventarioRepository inventarioRepository;

    public InventarioService(InventarioRepository inventarioRepository) {
        this.inventarioRepository = inventarioRepository;
    }

    @Transactional
    public boolean reservarStock(String productoId, int cantidad) {
        Inventario inv = inventarioRepository.findByProductoId(productoId)
                .orElseGet(() -> inventarioRepository.save(new Inventario(productoId, 100)));

        if (inv.getStockDisponible() >= cantidad) {
            inv.setStockDisponible(inv.getStockDisponible() - cantidad);
            inv.setStockReservado(inv.getStockReservado() + cantidad);
            inventarioRepository.save(inv);
            return true;
        }
        return false;
    }

    @Transactional
    public void liberarStock(String productoId, int cantidad) {
        Inventario inv = inventarioRepository.findByProductoId(productoId)
                .orElseThrow(() -> new RuntimeException("Producto no encontrado para compensación"));

        inv.setStockDisponible(inv.getStockDisponible() + cantidad);
        inv.setStockReservado(Math.max(0, inv.getStockReservado() - cantidad));
        inventarioRepository.save(inv);
    }
}
