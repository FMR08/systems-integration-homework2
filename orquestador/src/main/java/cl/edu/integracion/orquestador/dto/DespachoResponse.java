package cl.edu.integracion.orquestador.dto;

import cl.edu.integracion.orquestador.model.EstadoDespacho;

public class DespachoResponse {
    private boolean success;
    private String message;
    private String transaccionId;
    private EstadoDespacho estado;
    private String trackingNumber;

    public DespachoResponse(boolean success, String message, String transaccionId,
                            EstadoDespacho estado, String trackingNumber) {
        this.success = success;
        this.message = message;
        this.transaccionId = transaccionId;
        this.estado = estado;
        this.trackingNumber = trackingNumber;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getTransaccionId() { return transaccionId; }
    public EstadoDespacho getEstado() { return estado; }
    public String getTrackingNumber() { return trackingNumber; }
}
