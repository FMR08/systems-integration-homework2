package cl.edu.integracion.orquestador.dto;

import cl.edu.integracion.orquestador.model.EstadoDespacho;

public record DespachoResponse(
    boolean success,
    String message,
    String transaccionId,
    EstadoDespacho estado,
    String trackingNumber
) {
}
