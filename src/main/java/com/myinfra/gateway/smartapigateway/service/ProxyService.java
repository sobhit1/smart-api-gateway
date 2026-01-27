package com.myinfra.gateway.smartapigateway.service;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ProxyService {

    private final WebClient webClient;

    public ProxyService(@NonNull WebClient.Builder webClientBuilder) {
        Objects.requireNonNull(webClientBuilder, "WebClient.Builder must not be null");

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
        if (prefix == null || prefix.isBlank()) {
            log.error("Project prefix is not configured");
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }

        String targetUrl = config.getTargetUrl();
        if (targetUrl == null || targetUrl.isBlank()) {
            log.error("Target URL is not configured for project {}", prefix);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }

        String path = request.getURI() != null ? request.getURI().getPath() : "";
        String downstreamPath = stripPrefix(path, prefix);

        StringBuilder sb = new StringBuilder(targetUrl);
        sb.append(downstreamPath);
        if (request.getURI() != null && request.getURI().getQuery() != null) {
            sb.append('?').append(request.getURI().getQuery());
        }

        final URI targetUri;
        try {
            targetUri = Objects.requireNonNull(URI.create(sb.toString()), "targetUri must not be null");
        } catch (Exception e) {
            log.error("Invalid target URI for project {}: {}", prefix, e.getMessage());
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response.setComplete();
        }

        log.debug("Proxying: {} -> {}", path, targetUri);

        HttpHeaders filteredHeaders = new HttpHeaders();
        HttpHeaders incoming = request.getHeaders();
        if (incoming != null) {
            for (String key : incoming.keySet()) {
                if (key == null) continue;
                String k = key.trim();
                if (k.isEmpty()) continue;
                String lk = k.toLowerCase(Locale.ROOT);

                if (k.equalsIgnoreCase("Host")
                        || k.equalsIgnoreCase("Connection")
                        || k.equalsIgnoreCase("Keep-Alive")
                        || k.equalsIgnoreCase("Proxy-Authorization")
                        || k.equalsIgnoreCase("Proxy-Authenticate")
                        || k.equalsIgnoreCase("Transfer-Encoding")
                        || k.equalsIgnoreCase("Content-Length")
                        || lk.startsWith("x-user-")) {
                    continue;
                }

                List<String> values = incoming.get(key);
                if (values == null) continue;
                for (String v : values) {
                    if (v != null) filteredHeaders.add(k, v);
                }
            }
        }

        if (identity.id() != null && !identity.id().isBlank()) filteredHeaders.set("X-User-Id", identity.id());
        if (identity.role() != null && !identity.role().isBlank()) filteredHeaders.set("X-User-Role", identity.role());
        if (identity.plan() != null && !identity.plan().isBlank()) filteredHeaders.set("X-User-Plan", identity.plan());

        HttpMethod method = request.getMethod() != null ? request.getMethod() : HttpMethod.GET;

        Flux<DataBuffer> bodyFlux = Objects.requireNonNull(request.getBody(), "request.getBody() must not be null");

        return webClient
                .method(method)
                .uri(targetUri)
                .headers(h -> h.addAll(filteredHeaders))
                .body(BodyInserters.fromDataBuffers(bodyFlux))
                .exchangeToMono(backendResponse -> {
                    if (backendResponse.statusCode() != null) {
                        response.setStatusCode(backendResponse.statusCode());
                    }

                    HttpHeaders backendHeaders = backendResponse.headers().asHttpHeaders();
                    if (backendHeaders != null) {
                        for (String key : backendHeaders.keySet()) {
                            if (key == null) continue;
                            String k = key.trim();
                            if (k.isEmpty()) continue;
                            String lk = k.toLowerCase(Locale.ROOT);
                            if (k.equalsIgnoreCase("Transfer-Encoding")
                                    || k.equalsIgnoreCase("Connection")
                                    || k.equalsIgnoreCase("Keep-Alive")
                                    || lk.equals("content-length")) {
                                continue;
                            }
                            List<String> values = backendHeaders.get(key);
                            if (values == null) continue;
                            for (String v : values) {
                                if (v != null) response.getHeaders().add(key, v);
                            }
                        }
                    }

                    return response.writeWith(backendResponse.bodyToFlux(DataBuffer.class));
                })
                .onErrorResume(e -> {
                    log.error("Proxy Error for {}: {}", targetUri, e.getMessage());
                    if (e instanceof WebClientResponseException wcre && wcre.getStatusCode() != null) {
                        response.setStatusCode(wcre.getStatusCode());
                    } else {
                        response.setStatusCode(HttpStatus.BAD_GATEWAY);
                    }
                    return response.setComplete();
                });
    }

    /**
     * Removes the configured project prefix from the incoming request path
     * before forwarding it to the backend service.
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
}