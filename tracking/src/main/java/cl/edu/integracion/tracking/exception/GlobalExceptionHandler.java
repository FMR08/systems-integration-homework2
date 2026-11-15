package cl.edu.integracion.tracking.exception;

import cl.edu.integracion.tracking.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatusException(
            ResponseStatusException ex, HttpServletRequest request) {

        String errorCode = switch (ex.getStatusCode().value()) {
            case 400 -> "VALIDATION_ERROR";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "BUSINESS_CONFLICT";
            default -> "INTERNAL_ERROR";
        };

        ErrorResponse body = new ErrorResponse(
                errorCode,
                ex.getReason(),
                Collections.emptyList(),
                request.getRequestURI()
        );

        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
}
