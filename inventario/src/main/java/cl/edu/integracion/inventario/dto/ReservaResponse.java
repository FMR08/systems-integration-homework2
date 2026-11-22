package cl.edu.integracion.inventario.dto;

public class ReservaResponse {
    private boolean success;
    private String message;
    private String transaccionId;

    public ReservaResponse(final boolean success, final String message, final String transaccionId) {
        this.success = success;
        this.message = message;
        this.transaccionId = transaccionId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(final boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    public String getTransaccionId() {
        return transaccionId;
    }

    public void setTransaccionId(final String transaccionId) {
        this.transaccionId = transaccionId;
    }
}
