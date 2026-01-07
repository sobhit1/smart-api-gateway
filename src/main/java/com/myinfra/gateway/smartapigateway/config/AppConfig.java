package com.myinfra.gateway.smartapigateway.config;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Maps the 'application.yml' configuration to Java objects.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "")
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
        @NotNull
        private String prefix;
        
        @NotNull
        private String targetUrl;
        private Duration timeout;

        private AuthType authType;
        private String jwtSecret;
        private String sessionCookie;
        private boolean csrfRequired; 

        private List<String> publicPaths;

        private RateLimitConfig rateLimit;
    }

    @Data
    public static class RateLimitConfig {
        private long capacity;
        private long refillRate;
    }

    public enum AuthType {
        JWT,
        SESSION
    }
}