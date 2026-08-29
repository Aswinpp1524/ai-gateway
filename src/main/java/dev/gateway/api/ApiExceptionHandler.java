package dev.gateway.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import dev.gateway.core.ProviderException;
import dev.gateway.core.router.NoProviderAvailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoProviderAvailableException.class)
    ResponseEntity<ErrorResponse> handleNoProvider(NoProviderAvailableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage(), "invalid_request_error"));
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ErrorResponse> handleProviderException(ProviderException ex) {
        HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ErrorResponse(ex.getMessage(), "provider_error"));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(message, "invalid_request_error"));
    }

    /**
     * Catches HTTP message read failures (malformed JSON, type mismatches during binding, etc).
     * WebFlux's default handling surfaces only "Failed to read HTTP message" and drops the actual
     * cause (e.g. the underlying Jackson exception), which makes these painful to debug from the
     * response alone. WebExchangeBindException, a subtype, is matched first for validation failures.
     */
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ErrorResponse> handleServerWebInputException(ServerWebInputException ex) {
        String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(message, "invalid_request_error"));
    }
}