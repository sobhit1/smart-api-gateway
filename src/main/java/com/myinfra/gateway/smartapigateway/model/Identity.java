package com.myinfra.gateway.smartapigateway.model;

/**
 * Standardized Identity object representing an authenticated user.
 *
 * @param id   The unique user ID (e.g., "u_12345")
 * @param role The user's role (e.g., "ROLE_USER", "ROLE_ADMIN")
 * @param plan The user's subscription plan (e.g., "FREE", "PRO")
 */
public record Identity(
        String id,
        String role,
        String plan) {
}