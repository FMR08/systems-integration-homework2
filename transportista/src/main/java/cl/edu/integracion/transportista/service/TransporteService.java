package cl.edu.integracion.transportista.service;

import cl.edu.integracion.transportista.model.RetiroTransporte;
import cl.edu.integracion.transportista.repository.TransporteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransporteService {

    private final TransporteRepository transporteRepository;

    public TransporteService(TransporteRepository transporteRepository) {
        this.transporteRepository = transporteRepository;
    }

    @Transactional
    public String agendarRetiro(String transaccionId, String direccion) {
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }
        RetiroTransporte existente = transporteRepository.findByTransaccionId(transaccionId).orElse(null);
        if (existente != null) {
            if ("CANCELADO".equals(existente.getEstado())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El retiro de esta transacción ya fue cancelado");
            }
            return "Retiro ya agendado (idempotente)";
        }
        transporteRepository.save(new RetiroTransporte(transaccionId, direccion, "AGENDADO"));
        return "Retiro agendado con éxito";
    }

    @Transactional
    public String cancelarRetiro(String transaccionId) {
        if (transaccionId == null || transaccionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaccionId es obligatorio");
        }
        RetiroTransporte retiro = transporteRepository.findByTransaccionId(transaccionId).orElse(null);
        if (retiro == null) {
            return "Sin retiro para esta transacción: nada que cancelar";
        }
        if (!"CANCELADO".equals(retiro.getEstado())) {
            retiro.setEstado("CANCELADO");
            transporteRepository.save(retiro);
        }
        return "Retiro cancelado (compensación aplicada)";
    }
}
