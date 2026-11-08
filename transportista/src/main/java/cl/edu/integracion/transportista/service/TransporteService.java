package cl.edu.integracion.transportista.service;

import cl.edu.integracion.transportista.model.RetiroTransporte;
import cl.edu.integracion.transportista.repository.TransporteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransporteService {

    private final TransporteRepository transporteRepository;

    public TransporteService(TransporteRepository transporteRepository) {
        this.transporteRepository = transporteRepository;
    }

    @Transactional
    public boolean agendarRetiro(String transaccionId, String direccion) {
        if (transporteRepository.findByTransaccionId(transaccionId).isPresent()) {
            return true;
        }

        RetiroTransporte retiro = new RetiroTransporte(transaccionId, direccion, "AGENDADO");
        transporteRepository.save(retiro);
        return true;
    }

    @Transactional
    public void cancelarRetiro(String transaccionId) {
        RetiroTransporte retiro = transporteRepository.findByTransaccionId(transaccionId)
                .orElseThrow(() -> new RuntimeException("Retiro no encontrado para cancelar"));

        retiro.getEstado();
        retiro.setEstado("CANCELADO");
        transporteRepository.save(retiro);
    }
}
