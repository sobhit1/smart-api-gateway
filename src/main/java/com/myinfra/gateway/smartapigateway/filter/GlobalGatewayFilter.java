package com.myinfra.gateway.smartapigateway.filter;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import com.myinfra.gateway.smartapigateway.service.AuthService;
import com.myinfra.gateway.smartapigateway.service.ProjectResolver;
import com.myinfra.gateway.smartapigateway.service.ProxyService;
import com.myinfra.gateway.smartapigateway.service.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Global API Gateway filter that enforces routing, authentication,
 * rate limiting, and identity propagation for all incoming requests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalGatewayFilter implements GlobalFilter, Ordered {

    private final ProjectResolver projectResolver;
    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final ProxyService proxyService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Main gateway filter method executed for every HTTP request.
     *
     * @param exchange The current server exchange (request + response context)
     * @param chain    The gateway filter chain
     * @return Mono<Void> indicating request completion
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        
        String path = request.getURI().getPath();
        String host = request.getHeaders().getFirst("Host");

        Optional<ProjectConfig> configOpt = projectResolver.resolve(path);

        if (configOpt.isEmpty()) {
            log.warn("404 - No project matched for host: {} path: {}", host, path);
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return response.setComplete();
        }
        ProjectConfig config = configOpt.get();

        if (Boolean.TRUE.equals(config.isCsrfRequired()) && isWriteRequest(request)) {
            String csrfToken = request.getHeaders().getFirst("X-XSRF-TOKEN");
            if (csrfToken == null || csrfToken.isBlank()) {
                log.warn("CSRF attempt blocked for path: {}", path);
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return response.setComplete();
            }
        }

        return authService.authenticate(request, config)
            .switchIfEmpty(Mono.defer(() -> {
                if (isPublicPath(path, config)) {
                    return Mono.just(new Identity("anonymous", "ROLE_ANONYMOUS", "FREE"));
                }
                return Mono.empty();
            }))
            .flatMap(identity -> {
                String ipAddress = getClientIp(request);
                return rateLimiter.isAllowed(config, identity, ipAddress)
                    .flatMap(allowed -> {
                        if (!allowed) {
                            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return response.setComplete();
                        }

                        return proxyService.forward(exchange, config, identity);
                    });
            })
            .switchIfEmpty(Mono.defer(() -> {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return response.setComplete();
            }));
    }

    /**
     * Checks whether the request is a write request.
     *
     * @param request The incoming HTTP request
     * @return true if the request is a write request
     */
    private boolean isWriteRequest(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        return method == HttpMethod.POST || 
               method == HttpMethod.PUT || 
               method == HttpMethod.DELETE || 
               method == HttpMethod.PATCH;
    }

    /**
     * Checks whether the requested path is publicly accessible.
     *
     * @param path   The request URI path
     * @param config The project configuration
     * @return true if the path matches any configured public pattern
     */
    private boolean isPublicPath(String path, ProjectConfig config) {
        if (config == null || config.getPublicPaths() == null) return false;

        if (path == null) path = "";

        for (String pattern : config.getPublicPaths()) {
            if (pattern == null || pattern.isBlank()) continue;

            try {
                if (pathMatcher.match(pattern, path)) return true;
            } catch (IllegalArgumentException e) {
                log.warn("Invalid public-path pattern '{}', skipping. path='{}' err={}", pattern, path, e.getMessage());
            }
        }
        return false;
    }

     /**
     * Resolves the client's IP address for rate limiting and abuse detection.
     * Prefers X-Forwarded-For header (for proxy environments).
     *
     * @param request The incoming HTTP request
     * @return Client IP address or "unknown" if not resolvable
     */
    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return (remoteAddress != null) ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Ensures this filter runs before most other gateway filters.
     *
     * @return filter order priority
     */
    @Override
    public int getOrder() {
        return -1;
    }
}