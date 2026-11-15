package cl.edu.integracion.orquestador.dto;

public class TransportePasoRequest {
    private String transaccionId;
    private String direccion;

    public TransportePasoRequest(String transaccionId, String direccion) {
        this.transaccionId = transaccionId;
        this.direccion = direccion;
    }

    public String getTransaccionId() { return transaccionId; }
    public String getDireccion() { return direccion; }
}
