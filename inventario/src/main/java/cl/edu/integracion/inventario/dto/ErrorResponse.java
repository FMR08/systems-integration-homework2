package cl.edu.integracion.inventario.dto;

import java.time.Instant;
import java.util.List;

public class ErrorResponse {
    private final boolean success = false;
    private final ErrorDetail error;
    private final Instant timestamp;
    private final String path;

    public ErrorResponse(
        final String code,
        final String message,
        final List<FieldErrorDetail> details,
        final String path
    ) {
        this.error = new ErrorDetail(code, message, details);
        this.timestamp = Instant.now();
        this.path = path;
    }

    public record ErrorDetail(
        String code,
        String message,
        List<FieldErrorDetail> details
    ) {
    }

    public record FieldErrorDetail(
        String field,
        String message
    ) {
    }

    public boolean isSuccess() {
        return success;
    }

    public ErrorDetail getError() {
        return error;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getPath() {
        return path;
    }
}
