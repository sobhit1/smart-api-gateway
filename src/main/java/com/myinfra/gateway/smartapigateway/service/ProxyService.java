package com.myinfra.gateway.smartapigateway.service;

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
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class ProxyService {

    private final WebClient webClient;
    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public ProxyService(WebClient.Builder webClientBuilder,
                        ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        Objects.requireNonNull(webClientBuilder, "WebClient.Builder must not be null");
        this.circuitBreakerFactory = Objects.requireNonNull(circuitBreakerFactory, "CircuitBreakerFactory must not be null");

        HttpClient httpClient = Objects.requireNonNull(
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                        .responseTimeout(Duration.ofSeconds(60)),
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
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Invalid configuration for project"
                    )
            );
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
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Invalid target URI: " + e.getMessage()
                    )
            );
        }
        final URI targetUri = Objects.requireNonNull(uri);

        log.debug("Proxying: {} -> {}", path, targetUri);

        HttpHeaders filteredHeaders = new HttpHeaders();
        request.getHeaders().forEach((key, values) -> {
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
                    if (backendResponse.statusCode().is5xxServerError()) {
                        return Mono.error(
                                new ResponseStatusException(
                                        backendResponse.statusCode(),
                                        "Downstream service error"
                                )
                        );
                    }

                    response.setStatusCode(backendResponse.statusCode());

                    backendResponse.headers().asHttpHeaders()
                            .forEach((key, values) -> {
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

        return circuitBreaker.run(backendCall);
    }

    /**
     * Strips the specified prefix from the given path.
     *
     * @param path   Full incoming request path (e.g., "/shop/orders")
     * @param prefix Project prefix configured in gateway (e.g., "/shop")
     * @return Path without the project prefix (e.g., "/orders")
     */
    private String stripPrefix(String path, String prefix) {
        if (path == null) return "/";
        if (prefix == null || prefix.isEmpty()) return path;
        if (path.startsWith(prefix)) {
            String stripped = path.substring(prefix.length());
            return stripped.isEmpty() ? "/" : stripped;
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