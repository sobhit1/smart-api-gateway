package com.myinfra.gateway.smartapigateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Global Exception Handler for the API Gateway.
 * Intercepts all unhandled exceptions in the reactive chain and ensures
 * the client always receives a standardized JSON response (ErrorResponse).
 */
@Slf4j
@Component
@Order(-2) // High priority: Runs before Spring Boot's DefaultErrorWebExceptionHandler (Order -1)
@RequiredArgsConstructor
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * Entry point called by the WebFlux dispatcher for every unhandled exception.
     *
     * @param exchange Current server exchange (request/response context)
     * @param ex       The unhandled exception
     * @return {@code Mono<Void>} completing when the error response has been written
     */
    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {

        ServerHttpResponse response = exchange.getResponse();

        if (response.isCommitted()) {
            log.warn("Response already committed, cannot write error for path={} error={}",
                    exchange.getRequest().getURI().getPath(), ex.getMessage());
            return Objects.requireNonNull(Mono.<Void>error(ex));
        }

        Throwable cause = unwrapCause(ex);

        HttpStatus status;
        String message;

        if (cause instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            String reason = rse.getReason();
            message = (reason != null && !reason.isBlank())
                    ? reason
                    : status.getReasonPhrase();
        } else if (cause instanceof CallNotPermittedException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Service is temporarily unavailable. Circuit breaker is open.";
        } else if (cause instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "The upstream service did not respond in time. Please retry.";
        } else if (cause instanceof ConnectException) {
            status = HttpStatus.BAD_GATEWAY;
            message = "Could not connect to the upstream service.";
        } else if (cause instanceof JwtException) {
            status = HttpStatus.UNAUTHORIZED;
            message = "Invalid or malformed authentication token.";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected error occurred.";
        }

        String path = exchange.getRequest().getURI().getPath();

        Throwable logEx = (cause != null) ? cause : ex;

        if (status.is5xxServerError()) {
            log.error("Gateway error — status={} path={} error={}",
                    status.value(), path, logEx.getMessage(), logEx);
        } else {
            log.warn("Gateway error — status={} path={} error={}",
                    status.value(), path, logEx.getMessage());
        }

        ErrorResponse errorResponse = ErrorResponse.of(
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );

        return Objects.requireNonNull(writeErrorResponse(response, status, errorResponse));
    }

    /**
     * Serializes the {@link ErrorResponse} to JSON bytes and writes them into
     * the reactive response with the correct {@code Content-Type} header.
     *
     * @param response      The server HTTP response
     * @param status        HTTP status to set on the response
     * @param errorResponse The payload to serialize
     * @return {@code Mono<Void>} completing when the write is done
     */
    @NonNull
    private Mono<Void> writeErrorResponse(ServerHttpResponse response,
                                          HttpStatus status,
                                          ErrorResponse errorResponse) {

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorResponse);
        } catch (JsonProcessingException jsonEx) {
            log.error("Failed to serialize ErrorResponse to JSON", jsonEx);

            String fallbackTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            String fallbackJson = String.format(
                    "{\"timestamp\":\"%s\",\"status\":500,\"error\":\"Internal Server Error\",\"message\":\"Error serialization failed.\",\"path\":\"%s\"}",
                    fallbackTime, errorResponse.getPath()
            );
            bytes = fallbackJson.getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(Objects.requireNonNull(bytes));
        
        return response.writeWith(Objects.requireNonNull(Mono.just(buffer)));
    }

    /**
     * Walks the exception cause chain to find the deepest root cause.
     * This is important for properly classifying wrapped exceptions (e.g., a
     * {@link TimeoutException} wrapped inside a {@code ReactiveException}).
     *
     * @param t The top-level throwable
     * @return The root cause
     */
    private Throwable unwrapCause(Throwable t) {
        Throwable curr = t;
        Throwable rse = null;
        Throwable deepest = t;

        int depth = 0;
        while (curr != null && depth++ < 10) {
            if (curr instanceof ResponseStatusException) {
                rse = curr;
                break;
            }
            deepest = curr;
            curr = curr.getCause();
        }

        return (rse != null) ? rse : deepest;
    }
}