package com.myinfra.gateway.smartapigateway.service;

import com.myinfra.gateway.smartapigateway.config.AppConfig;
import com.myinfra.gateway.smartapigateway.config.AppConfig.ProjectConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectResolver {

    private final AppConfig appConfig;

    /**
     * Identifies the project configuration for an incoming request.
     *
     * @param path     The request URI path (e.g., "/shop/api/orders")
     * @param hostname The Host header value (e.g., "api.myinfra.com")
     * @return Optional containing the matching ProjectConfig, or empty if no match found.
     */
    public Optional<ProjectConfig> resolve(String path, String hostname) {
        if (appConfig.getProjects() == null || appConfig.getProjects().isEmpty()) {
            log.warn("No projects configured in application.yml");
            return Optional.empty();
        }

        return appConfig.getProjects().values().stream()
                .filter(config -> path.startsWith(config.getPrefix()))
                .findFirst();
    }
}