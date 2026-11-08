package cl.edu.integracion.transportista.dto;

public class TransporteResponse {
    private boolean success;
    private String message;
    private String transaccionId;

    public TransporteResponse(boolean success, String message, String transaccionId) {
        this.success = success;
        this.message = message;
        this.transaccionId = transaccionId;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTransaccionId() { return transaccionId; }
    public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
}
