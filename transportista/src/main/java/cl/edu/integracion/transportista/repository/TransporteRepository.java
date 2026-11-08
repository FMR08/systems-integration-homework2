package cl.edu.integracion.transportista.repository;

import cl.edu.integracion.transportista.model.RetiroTransporte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransporteRepository extends JpaRepository<RetiroTransporte, Long> {
    Optional<RetiroTransporte> findByTransaccionId(String transaccionId);
}
