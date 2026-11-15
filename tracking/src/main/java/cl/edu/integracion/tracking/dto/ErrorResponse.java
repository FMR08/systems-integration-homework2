package cl.edu.integracion.tracking.dto;

import java.time.Instant;
import java.util.List;

public class ErrorResponse {
    private boolean success = false;
    private ErrorDetail error;
    private Instant timestamp;
    private String path;

    public ErrorResponse(String code, String message, List<FieldErrorDetail> details, String path) {
        this.error = new ErrorDetail(code, message, details);
        this.timestamp = Instant.now();
        this.path = path;
    }

    public static class ErrorDetail {
        private String code;
        private String message;
        private List<FieldErrorDetail> details;

        public ErrorDetail(String code, String message, List<FieldErrorDetail> details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }

        public String getCode() { return code; }
        public String getMessage() { return message; }
        public List<FieldErrorDetail> getDetails() { return details; }
    }

    public static class FieldErrorDetail {
        private String field;
        private String message;

        public FieldErrorDetail(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public String getField() { return field; }
        public String getMessage() { return message; }
    }

    public boolean isSuccess() { return success; }
    public ErrorDetail getError() { return error; }
    public Instant getTimestamp() { return timestamp; }
    public String getPath() { return path; }
}
