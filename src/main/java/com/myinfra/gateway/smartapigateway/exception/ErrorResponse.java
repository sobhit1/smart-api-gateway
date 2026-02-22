package com.myinfra.gateway.smartapigateway.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Standardized immutable response model for all API Gateway errors.
 */
@Getter
@Builder
public class ErrorResponse {

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime timestamp;

    private final int status;

    private final String error;

    private final String message;

    private final String path;

    /**
     * Static factory method for convenient instantiation.
     * Automatically sets the current server time for the timestamp.
     *
     * @param status  HTTP status code
     * @param error   HTTP reason phrase
     * @param message Detailed error message
     * @param path    Request URI path
     * @return A fully constructed ErrorResponse instance
     */
    public static ErrorResponse of(int status, String error, String message, String path) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .message(message)
                .path(path)
                .build();
    }
}