package com.myinfra.gateway.smartapigateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ResilienceConfig {

    private final AppConfig appConfig;

    /**
     * Configures default Circuit Breaker and Time Limiter settings
     * based on project-specific configurations defined in AppConfig.
     *
     * @return Customizer for ReactiveResilience4JCircuitBreakerFactory
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> {

            AppConfig.ProjectConfig projectConfig = findProjectConfig(id);

            float failureRate = 50.0f;
            Duration waitDuration = Duration.ofSeconds(10);
            int slidingWindow = 10;
            int permittedCalls = 3;

            if (projectConfig != null) {
                AppConfig.CircuitBreakerConfig myConfig = projectConfig.getCircuitBreaker();
                if (myConfig != null) {
                    failureRate = myConfig.getFailureRateThreshold();
                    waitDuration = myConfig.getWaitDuration();
                    slidingWindow = myConfig.getSlidingWindowSize();
                    permittedCalls = myConfig.getPermittedNumberOfCallsInHalfOpenState();
                    log.debug("Configuring Circuit Breaker for '{}': failureRate={}%, wait={}s", id, failureRate,
                            waitDuration.getSeconds());
                }
            } else {
                log.debug("No specific config found for '{}', using defaults.", id);
            }

            CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(slidingWindow)
                    .failureRateThreshold(failureRate)
                    .waitDurationInOpenState(waitDuration)
                    .permittedNumberOfCallsInHalfOpenState(permittedCalls)
                    .build();

            TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofDays(1))
                    .build();

            return new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(circuitBreakerConfig)
                    .timeLimiterConfig(timeLimiterConfig)
                    .build();
        });
    }

    /**
     * Finds the ProjectConfig by its prefix ID.
     *
     * @param id The project prefix
     * @return The corresponding ProjectConfig or null if not found
     */
    private AppConfig.ProjectConfig findProjectConfig(String id) {
        if (appConfig.getProjects() == null)
            return null;
        for (AppConfig.ProjectConfig config : appConfig.getProjects().values()) {
            if (id.equals(config.getPrefix())) {
                return config;
            }
        }
        return null;
    }
}