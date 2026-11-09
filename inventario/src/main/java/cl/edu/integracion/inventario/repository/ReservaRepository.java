package cl.edu.integracion.inventario.repository;

import cl.edu.integracion.inventario.model.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservaRepository extends JpaRepository<Reserva, Long> {
    Optional<Reserva> findByTransaccionId(String transaccionId);
}
