package com.myinfra.gateway.smartapigateway.service;

import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import com.myinfra.gateway.smartapigateway.model.Identity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
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

import java.security.PublicKey;
import java.util.Base64;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final ReactiveStringRedisTemplate redisTemplate;

     /**
     * Authenticates an incoming request based on project configuration.
     *
     * @param request The incoming HTTP request
     * @param config  The matched project configuration
     * @return Mono<Identity> if authentication succeeds, or Mono.empty() if it fails
     */
    public Mono<Identity> authenticate(ServerHttpRequest request, ProjectConfig config) {
        if (config.getAuthType() == null) return Mono.empty();

        return switch (config.getAuthType()) {
            case JWT -> authenticateJwt(request, config);
            case SESSION -> authenticateSession(request, config);
        };
    }

    /**
     * Performs JWT-based authentication.
     * Extracts the token from header or cookie, validates its signature,
     * and converts claims into an {@link Identity}.
     *
     * @param request Incoming HTTP request
     * @param config  Project configuration containing JWT settings
     * @return Mono<Identity> if token is valid, otherwise Mono.empty()
     */
    private Mono<Identity> authenticateJwt(ServerHttpRequest request, ProjectConfig config) {
        String token = extractToken(request, config);

        if (token == null) return Mono.empty();

        try {
            JwtParserBuilder parserBuilder = Jwts.parser();

            if (config.getJwtPublicKey() != null && !config.getJwtPublicKey().isBlank()) {
                // Asymmetric (RS256)
                PublicKey publicKey = parsePublicKey(config.getJwtPublicKey());
                parserBuilder.verifyWith(publicKey);
            } else if (config.getJwtSecret() != null) {
                // Symmetric (HS256)
                byte[] keyBytes = Base64.getDecoder().decode(config.getJwtSecret());
                SecretKey key = Keys.hmacShaKeyFor(keyBytes);
                parserBuilder.verifyWith(key);
            } else {
                log.error("No JWT key configured for project {}", config.getPrefix());
                return Mono.empty();
            }

            Claims claims = parserBuilder.build()
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

    /**
     * Extracts JWT token from either:
     * - Authorization header (Bearer token)
     * - Configured cookie (if defined)
     *
     * @param request Incoming HTTP request
     * @param config  Project configuration
     * @return JWT token string or null if not found
     */
    private String extractToken(ServerHttpRequest request, ProjectConfig config) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        String jwtCookie = config.getJwtCookie();
        if (jwtCookie != null && !jwtCookie.isBlank()) {
            HttpCookie cookie = request.getCookies().getFirst(jwtCookie);
            if (cookie != null) {
                return cookie.getValue();
            }
        }

        return null;
    }

    /**
     * Parses a Base64-encoded public key string into a PublicKey object.
     *
     * @param base64PublicKey Base64-encoded public key
     * @return PublicKey instance
     * @throws Exception if parsing fails
     */
    private PublicKey parsePublicKey(String base64PublicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    /**
     * Performs session-based authentication using Redis.
     * Looks up the session ID from cookies and verifies its existence in Redis.
     *
     * @param request Incoming HTTP request
     * @param config  Project configuration containing session settings
     * @return Mono<Identity> if session exists, otherwise Mono.empty()
     */
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