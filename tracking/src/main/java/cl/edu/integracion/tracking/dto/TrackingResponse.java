package cl.edu.integracion.tracking.dto;

public class TrackingResponse {
    private boolean success;
    private String message;
    private String trackingNumber;
    private String transaccionId;

    public TrackingResponse(
        final boolean success,
        final String message,
        final String trackingNumber,
        final String transaccionId
    ) {
        this.success = success;
        this.message = message;
        this.trackingNumber = trackingNumber;
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

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(final String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public String getTransaccionId() {
        return transaccionId;
    }

    public void setTransaccionId(final String transaccionId) {
        this.transaccionId = transaccionId;
    }
}
