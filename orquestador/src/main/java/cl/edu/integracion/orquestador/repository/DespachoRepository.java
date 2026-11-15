package cl.edu.integracion.orquestador.repository;

import cl.edu.integracion.orquestador.model.Despacho;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DespachoRepository extends JpaRepository<Despacho, Long> {
    Optional<Despacho> findByTransaccionId(String transaccionId);
}
