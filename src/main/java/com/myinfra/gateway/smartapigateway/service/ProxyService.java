package com.myinfra.gateway.smartapigateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class ProxyService {

    private final WebClient webClient;
    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private final ObjectMapper objectMapper;

    public ProxyService(WebClient.Builder webClientBuilder,
                        ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory,
                        ObjectMapper objectMapper) {
        Objects.requireNonNull(webClientBuilder, "WebClient.Builder must not be null");
        this.circuitBreakerFactory = Objects.requireNonNull(circuitBreakerFactory, "CircuitBreakerFactory must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");

        HttpClient httpClient = Objects.requireNonNull(
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                        .responseTimeout(Duration.ofSeconds(5)),
                "HttpClient must not be null"
        );

        ClientHttpConnector connector = Objects.requireNonNull(
                new ReactorClientHttpConnector(httpClient),
                "ClientHttpConnector must not be null"
        );

        this.webClient = webClientBuilder
                .clientConnector(connector)
                .build();
    }

    /**
     * Forwards the incoming request to the downstream backend service.
     *
     * @param exchange Current HTTP exchange (request + response)
     * @param config   Project configuration containing target backend URL
     * @param identity Authenticated user identity
     * @return Mono<Void> completing when the backend response is written to client
     */
    public Mono<Void> forward(ServerWebExchange exchange, ProjectConfig config, Identity identity) {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(identity, "identity must not be null");

        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String prefix = config.getPrefix();
        String targetUrl = config.getTargetUrl();
        
        if (prefix == null || prefix.isBlank()
            || targetUrl == null || targetUrl.isBlank()) {
            log.error("Invalid configuration for project");
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }

        String path = request.getURI().getPath();
        String downstreamPath = stripPrefix(path, prefix);
        
        StringBuilder sb = new StringBuilder(targetUrl);
        sb.append(downstreamPath);
        if (request.getURI().getQuery() != null) {
            sb.append('?').append(request.getURI().getQuery());
        }

        URI uri;
        try {
            uri = URI.create(sb.toString());
        } catch (IllegalArgumentException e) {
            log.error("Invalid target URI: {}", e.getMessage());
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }
        final URI targetUri = Objects.requireNonNull(uri);

        log.debug("Proxying: {} -> {}", path, targetUri);

        HttpHeaders filteredHeaders = new HttpHeaders();
        HttpHeaders incoming = request.getHeaders();
        
        incoming.forEach((key, values) -> {
            if (key == null || values == null) return;
            if (!isIgnoredHeader(key)) {
                filteredHeaders.addAll(key, values);
            }
        });


        if (identity.id() != null) filteredHeaders.set("X-User-Id", identity.id());
        if (identity.role() != null) filteredHeaders.set("X-User-Role", identity.role());
        if (identity.plan() != null) filteredHeaders.set("X-User-Plan", identity.plan());

        HttpMethod method = request.getMethod() != null ? request.getMethod() : HttpMethod.GET;

        Flux<DataBuffer> bodyFlux = request.getBody();

        Mono<Void> backendCall = webClient
                .method(method)
                .uri(targetUri)
                .headers(h -> h.addAll(filteredHeaders))
                .body(BodyInserters.fromDataBuffers(bodyFlux))
                .exchangeToMono(backendResponse -> {
                    response.setStatusCode(backendResponse.statusCode());

                    HttpHeaders backendHeaders = backendResponse.headers().asHttpHeaders();
                    backendHeaders.forEach((key, values) -> {
                        if (key == null || values == null) return;
                        if (!isIgnoredHeader(key)) {
                            response.getHeaders().addAll(key, values);
                        }
                    });

                    return response.writeWith(backendResponse.bodyToFlux(DataBuffer.class));
                });

        if (config.getTimeLimiter() != null) {
            backendCall = backendCall.timeout(config.getTimeLimiter().getTimeout());
        }

        ReactiveCircuitBreaker circuitBreaker = circuitBreakerFactory.create(prefix);

        return circuitBreaker.run(backendCall, throwable -> resumeWithFallback(response, throwable));
    }

    /**
     * Fallback method invoked when Circuit Breaker is open or backend call fails.
     *
     * @param response ServerHttpResponse to write the fallback response
     * @param t        The throwable that caused the fallback
     * @return Mono<Void> completing when the fallback response is written
     */
    private Mono<Void> resumeWithFallback(ServerHttpResponse response, Throwable t) {
        log.error("Circuit Breaker Fallback triggered: {}", t.getMessage());

        if (response.isCommitted()) {
            return Mono.error(t);
        }

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        String error = "Service Unavailable";
        String message = "The backend service is currently unavailable.";

        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause instanceof TimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            error = "Gateway Timeout";
            message = "The request timed out waiting for the backend.";
        } 
        else if (cause instanceof java.net.ConnectException) {
            status = HttpStatus.BAD_GATEWAY;
            error = "Bad Gateway";
            message = "Could not connect to the backend service.";
        } 
        else if (cause.getClass().getName().contains("CallNotPermittedException")) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            error = "Circuit Breaker Open";
            message = "Circuit breaker is OPEN. Request failed fast.";
        }

        response.setStatusCode(status);

        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("status", status.value());
        errorDetails.put("error", error);
        errorDetails.put("message", message);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorDetails);
        } catch (JsonProcessingException e) {
            bytes = "{\"error\": \"Service Unavailable\"}".getBytes();
        }

        DataBuffer buffer = response.bufferFactory().wrap(Objects.requireNonNull(bytes));

        return response.writeWith(Objects.requireNonNull(Mono.just(buffer)));
    }

    /**
     * Strips the specified prefix from the given path.
     *
     * @param path   Full incoming request path (e.g., "/shop/orders")
     * @param prefix Project prefix configured in gateway (e.g., "/shop")
     * @return Path without the project prefix (e.g., "/orders")
     */
    private String stripPrefix(String path, String prefix) {
        if (path == null) return "";
        if (prefix == null || prefix.isEmpty()) return path;
        if (path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }
        return path;
    }

    /**
     * Checks whether the given header should be ignored.
     *
     * @param key The header key
     * @return true if the header should be ignored
     */
    private boolean isIgnoredHeader(String key) {
        if (key == null) return true;
        String lowerKey = key.toLowerCase(Locale.ROOT);
        return lowerKey.equals("host") 
                || lowerKey.equals("connection") 
                || lowerKey.equals("keep-alive") 
                || lowerKey.equals("transfer-encoding") 
                || lowerKey.equals("proxy-authorization")
                || lowerKey.equals("proxy-authenticate")
                || lowerKey.equals("content-length")
                || lowerKey.startsWith("x-user-");
    }
}