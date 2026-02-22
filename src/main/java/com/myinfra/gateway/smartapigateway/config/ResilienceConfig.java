package com.myinfra.gateway.smartapigateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        Map<String, AppConfig.ProjectConfig> projectByPrefix =
                appConfig.getProjects() == null
                        ? Map.of()
                        : appConfig.getProjects().values().stream()
                            .collect(Collectors.toMap(
                                    AppConfig.ProjectConfig::getPrefix,
                                    Function.identity()
                            ));

        return factory -> factory.configureDefault(id -> {

            AppConfig.ProjectConfig projectConfig = projectByPrefix.get(id);
            AppConfig.CircuitBreakerConfig cb =
                    projectConfig != null ? projectConfig.getCircuitBreaker() : null;

            float failureRateThreshold =
                    cb != null ? cb.getFailureRateThreshold() : 50.0f;

            int slidingWindowSize =
                    cb != null ? cb.getSlidingWindowSize() : 10;

            int permittedCallsInHalfOpen =
                    cb != null ? cb.getPermittedNumberOfCallsInHalfOpenState() : 3;

            Duration waitDuration =
                    cb != null ? cb.getWaitDuration() : Duration.ofSeconds(10);

            CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                            .slidingWindowSize(slidingWindowSize)
                            .failureRateThreshold(failureRateThreshold)
                            .waitDurationInOpenState(waitDuration)
                            .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
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
}