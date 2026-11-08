package cl.edu.integracion.tracking.dto;

public class TrackingResponse {
    private boolean success;
    private String message;
    private String trackingNumber;
    private String transaccionId;

    public TrackingResponse(boolean success, String message, String trackingNumber, String transaccionId) {
        this.success = success;
        this.message = message;
        this.trackingNumber = trackingNumber;
        this.transaccionId = transaccionId;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getTransaccionId() { return transaccionId; }
    public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
}
