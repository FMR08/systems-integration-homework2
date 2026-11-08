package cl.edu.integracion.inventario.dto;

public class ReservaResponse {
    private boolean success;
    private String message;
    private String transaccionId;

    public ReservaResponse(boolean success, String message, String transaccionId) {
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
