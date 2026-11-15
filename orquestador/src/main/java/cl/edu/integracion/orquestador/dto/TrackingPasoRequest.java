package cl.edu.integracion.orquestador.dto;

public class TrackingPasoRequest {
    private String transaccionId;

    public TrackingPasoRequest(String transaccionId) {
        this.transaccionId = transaccionId;
    }

    public String getTransaccionId() { return transaccionId; }
}
