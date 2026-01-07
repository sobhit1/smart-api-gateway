package com.myinfra.gateway.smartapigateway.service;

import module java.base;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<Identity> authenticate(ServerHttpRequest request, ProjectConfig config) {
        if (config.getAuthType() == null) {
            return Mono.empty();
        }

        return switch (config.getAuthType()) {
            case JWT -> authenticateJwt(request, config);
            case SESSION -> authenticateSession(request, config);
        };
    }

    private Mono<Identity> authenticateJwt(ServerHttpRequest request, ProjectConfig config) {
        String token = extractToken(request, config);

        if (token == null) {
            return Mono.empty();
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(config.getJwtSecret());
            SecretKey key = Keys.hmacShaKeyFor(keyBytes);

            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String role = claims.get("role", String.class);
            String plan = claims.get("plan", String.class);
            if (plan == null) plan = "FREE";

            return Mono.just(new Identity(userId, role, plan));

        } catch (Exception e) {
            log.warn("JWT Validation Failed: {}", e.getMessage());
            return Mono.empty();
        }
    }

    private String extractToken(ServerHttpRequest request, ProjectConfig config) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        if (config.getJwtCookie() != null) {
            HttpCookie cookie = request.getCookies().getFirst(config.getJwtCookie());
            if (cookie != null) {
                return cookie.getValue();
            }
        }

        return null;
    }

    private Mono<Identity> authenticateSession(ServerHttpRequest request, ProjectConfig config) {
        String cookieName = config.getSessionCookie();
        if (cookieName == null) {
            cookieName = "SESSION";
        }

        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null) {
            return Mono.empty();
        }

        String sessionId = cookie.getValue();
        String redisKey = "spring:session:sessions:" + sessionId;

        return redisTemplate.hasKey(redisKey)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? Mono.just(new Identity("session-user", "ROLE_USER", "FREE"))
                        : Mono.empty());

    }
}