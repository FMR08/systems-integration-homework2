package cl.edu.integracion.tracking.repository;

import cl.edu.integracion.tracking.model.TrackingEnvio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrackingRepository extends JpaRepository<TrackingEnvio, Long> {
    Optional<TrackingEnvio> findByTransaccionId(String transaccionId);
}
