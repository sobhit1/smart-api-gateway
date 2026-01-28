package com.myinfra.gateway.smartapigateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
@Validated
public class AppConfig {

    private GatewayConfig gatewayConfig;
    private Map<String, ProjectConfig> projects;

    @Data
    public static class GatewayConfig {
        private Duration globalTimeout;
    }

    @Data
    public static class ProjectConfig {
        @NotBlank
        private String prefix;

        @NotBlank
        private String targetUrl;

        @NotNull
        private AuthType authType;

        private String jwtSecret;
        private String jwtPublicKey;
        private String jwtCookie;
        private String sessionCookie;

        private boolean csrfRequired;
        private List<String> publicPaths;

        private RateLimitConfig rateLimit;
        private CircuitBreakerConfig circuitBreaker;
        private TimeLimiterConfig timeLimiter;
    }

    @Data
    public static class RateLimitConfig {
        private long capacity;
        private long refillRate;
    }

    @Data
    public static class CircuitBreakerConfig {
        private float failureRateThreshold = 50.0f;
        private Duration waitDuration = Duration.ofSeconds(10);
        private int slidingWindowSize = 10;
        private int permittedNumberOfCallsInHalfOpenState = 3;
    }

    @Data
    public static class TimeLimiterConfig {
        private Duration timeout = Duration.ofSeconds(5);
        private boolean cancelRunningFuture = true;
    }

    public enum AuthType {
        JWT,
        SESSION
    }
}