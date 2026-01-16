package com.myinfra.gateway.smartapigateway.service;

import module java.base;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.config.AppConfig.RateLimitConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final ReactiveStringRedisTemplate redisTemplate;
    private RedisScript<List<Long>> script;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void loadScript() {
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/request_rate_limiter.lua"));
        
        redisScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
        
        this.script = redisScript;
    }

    /**
     * Checks if the request is allowed based on the Token Bucket algorithm.
     * Supports fallback to IP-based rate limiting for unauthenticated users.
     *
     * @param config    The project configuration
     * @param identity  The authenticated user identity (nullable)
     * @param ipAddress The client's IP address (fallback key)
     * @return Mono<Boolean> - true if allowed
     */
    public Mono<Boolean> isAllowed(ProjectConfig config, Identity identity, String ipAddress) {
        if (config.getRateLimit() == null) {
            return Mono.just(true); 
        }

        RateLimitConfig rateConfig = config.getRateLimit();
        String finalKey = generateKey(config.getPrefix(), identity, ipAddress);

        List<String> keys = List.of(finalKey);

        List<String> args = List.of(
                String.valueOf(rateConfig.getCapacity()),
                String.valueOf(rateConfig.getRefillRate()),
                String.valueOf(System.currentTimeMillis()), 
                "1" 
        );

        return redisTemplate.execute(script, keys, args)
                .next()
                .map(result -> {
                    Long allowed = result.getFirst();
                    return allowed == 1L;
                })
                .doOnError(e -> log.error("Rate Limiter Redis Error: {}", e.getMessage()))
                .onErrorReturn(true);
    }

    /**
     * Generates the Redis key for rate limiting.
     * Logic: Use User ID if present; otherwise fallback to IP address.
     */
    private String generateKey(String prefix, Identity identity, String ipAddress) {
        if (identity != null && identity.id() != null) {
            // Authenticated User: rate_limit:/shop:user:u_123
            return "rate_limit:" + prefix + ":user:" + identity.id();
        } else {
            // Unauthenticated (Public) User: rate_limit:/shop:ip:192.168.1.1
            return "rate_limit:" + prefix + ":ip:" + ipAddress;
        }
    }
}