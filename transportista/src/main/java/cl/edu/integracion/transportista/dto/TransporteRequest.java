package cl.edu.integracion.transportista.dto;

public class TransporteRequest {
    private String transaccionId;
    private String direccion;

    public String getTransaccionId() { return transaccionId; }
    public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
}
