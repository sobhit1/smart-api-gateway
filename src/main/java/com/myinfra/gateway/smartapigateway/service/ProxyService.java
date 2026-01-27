package com.myinfra.gateway.smartapigateway.service;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ProxyService {

    private final WebClient webClient;

    public ProxyService(WebClient.Builder webClientBuilder) {

        HttpClient httpClient = HttpClient.create()
                // TCP connection timeout
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                // Max time to wait for backend response
                .responseTimeout(Duration.ofSeconds(5));

        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
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
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String path = request.getURI().getPath();
        String downstreamPath = stripPrefix(path, config.getPrefix());

        StringBuilder urlBuilder = new StringBuilder(config.getTargetUrl());
        urlBuilder.append(downstreamPath);

        if (request.getURI().getQuery() != null) {
            urlBuilder.append("?").append(request.getURI().getQuery());
        }

        String finalTargetUrl = urlBuilder.toString();

        log.debug("Proxying: {} -> {}", path, finalTargetUrl);

        var filteredHeaders = new HttpHeaders();
        request.getHeaders().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Host") && !k.toLowerCase().startsWith("x-user-")) {
                filteredHeaders.addAll(k, v);
            }
        });

        filteredHeaders.add("X-User-Id", identity.id());
        filteredHeaders.add("X-User-Role", identity.role());
        filteredHeaders.add("X-User-Plan", identity.plan());

        HttpMethod method = request.getMethod();

        return webClient
                .method(method)
                .uri(finalTargetUrl)
                .headers(headers -> headers.addAll(filteredHeaders))
                .body(BodyInserters.fromDataBuffers(request.getBody()))
                .exchangeToMono(backendResponse -> {
                    response.setStatusCode(backendResponse.statusCode());
                    
                    backendResponse.headers().asHttpHeaders().forEach((k, v) -> {
                        response.getHeaders().addAll(k, v);
                    });

                    return response.writeWith(backendResponse.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class));
                })
                .onErrorResume(e -> {
                    log.error("Proxy Error for {}: {}", finalTargetUrl, e.getMessage());
                    if (e instanceof WebClientResponseException wcre) {
                        response.setStatusCode(wcre.getStatusCode());
                    } else {
                        response.setStatusCode(org.springframework.http.HttpStatus.BAD_GATEWAY);
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
        if (path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }
        return path;
    }
}