package cl.edu.integracion.inventario.repository;

import cl.edu.integracion.inventario.model.Inventario;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventarioRepository extends JpaRepository<Inventario, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Inventario> findByProductoId(String productoId);
}
