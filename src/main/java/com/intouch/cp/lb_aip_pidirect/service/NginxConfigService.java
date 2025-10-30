package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.util.NginxConfigGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enhanced NginxConfigService with DUAL upstream support
 * Manages separate configurations for incoming and outgoing servers
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NginxConfigService {

    private final NginxConfig nginxConfig;
    private final NginxConfigGenerator configGenerator;
    private final RedisStateService redisStateService;

    private LocalDateTime lastConfigUpdate;
    private String currentConfigContent;

    @PostConstruct
    public void init() {
        log.info("=== Initializing NGINX Configuration Service (DUAL UPSTREAM MODE) ===");

        String configPath = nginxConfig.getNginx().getConfigPath();
        if (configPath == null) {
            log.error("NGINX config path is not properly configured!");
            return;
        }

        log.info("NGINX config directory: {}", nginxConfig.getNginx().getConfigDir());
        log.info("NGINX config file: {}", nginxConfig.getNginx().getConfigFile());
        log.info("Full config path: {}", configPath);
        log.info("Dual upstream enabled: {}", nginxConfig.isDualUpstreamEnabled());

        // Create config directory if it doesn't exist
        try {
            Path configDir = Paths.get(nginxConfig.getNginx().getConfigDir());
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                log.info("Created NGINX config directory: {}", configDir);
            }
        } catch (IOException e) {
            log.error("Failed to create config directory: {}", e.getMessage(), e);
        }

        // Load configuration from Redis if available
        syncConfigFromRedis();

        log.info("=== NGINX Configuration Service Initialized ===");
    }

    /**
     * Update upstream configuration with DUAL upstreams
     */
    public synchronized boolean updateDualUpstreamConfiguration(
            List<WeightAllocation> incomingWeights,
            List<WeightAllocation> outgoingWeights) {

        try {
            log.info("Updating DUAL upstream configuration - Incoming: {}, Outgoing: {}",
                    incomingWeights.size(), outgoingWeights.size());

            // Generate new configuration with both upstreams
            String newConfig = configGenerator.generateDualUpstreamConfig(
                    incomingWeights,
                    outgoingWeights
            );

            // Validate configuration
            if (!configGenerator.validateConfig(newConfig)) {
                log.error("Generated configuration failed validation");
                return false;
            }

            // Store in Redis for other instances to sync
            redisStateService.storeNginxConfig(newConfig);

            // Apply configuration locally
            return applyConfiguration(newConfig);

        } catch (Exception e) {
            log.error("Failed to update dual upstream configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * BACKWARD COMPATIBILITY: Update upstream configuration (single list)
     * Uses outgoing servers for backward compatibility
     */
    public synchronized boolean updateUpstreamConfiguration(List<WeightAllocation> weights) {
        log.info("updateUpstreamConfiguration() called - treating as outgoing servers");
        // For backward compatibility, treat as outgoing servers
        return updateDualUpstreamConfiguration(new ArrayList<>(), weights);
    }

    /**
     * Apply configuration to NGINX
     */
    private boolean applyConfiguration(String configContent) {
        try {
            String configPath = nginxConfig.getNginx().getConfigPath();

            // Write configuration to file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(configPath))) {
                writer.write(configContent);
            }

            log.info("Configuration written to: {}", configPath);

            // Store current config
            currentConfigContent = configContent;
            lastConfigUpdate = LocalDateTime.now();

            // Reload NGINX
            boolean reloaded = reloadNginx();

            if (reloaded) {
                log.info("NGINX configuration applied and reloaded successfully");
                return true;
            } else {
                log.error("NGINX reload failed");
                return false;
            }

        } catch (IOException e) {
            log.error("Failed to write configuration file: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reload NGINX configuration
     */
    private boolean reloadNginx() {
        try {
            String reloadCommand = nginxConfig.getNginx().getReloadCommand();
            log.info("Executing NGINX reload: {}", reloadCommand);

            Process process = Runtime.getRuntime().exec(reloadCommand);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("NGINX reloaded successfully");
                return true;
            } else {
                log.error("NGINX reload failed with exit code: {}", exitCode);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to reload NGINX: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Periodically sync configuration from Redis
     */
    @Scheduled(fixedDelayString = "#{${loadbalancer.redis.intervals.config-sync:10000}}")
    public void syncConfigFromRedis() {
        try {
            Optional<LocalDateTime> redisUpdateTime = redisStateService.getLastNginxUpdateTime();

            if (redisUpdateTime.isEmpty()) {
                log.debug("No configuration update time in Redis");
                return;
            }

            // If Redis has newer config, apply it
            if (lastConfigUpdate == null || redisUpdateTime.get().isAfter(lastConfigUpdate)) {
                log.info("Syncing newer configuration from Redis");

                Optional<String> redisConfig = redisStateService.getNginxConfig();
                if (redisConfig.isPresent()) {
                    applyConfiguration(redisConfig.get());
                    log.info("Configuration synced from Redis successfully");
                }
            }

        } catch (Exception e) {
            log.error("Error syncing config from Redis: {}", e.getMessage(), e);
        }
    }

    /**
     * Get current configuration
     */
    public String getCurrentConfig() {
        return currentConfigContent;
    }

    /**
     * BACKWARD COMPATIBILITY: Get current configuration
     */
    public String getCurrentConfiguration() {
        return getCurrentConfig();
    }

    /**
     * Get last update time
     */
    public LocalDateTime getLastConfigUpdate() {
        return lastConfigUpdate;
    }

    /**
     * BACKWARD COMPATIBILITY: Force refresh from Redis
     */
    public boolean forceRefreshFromRedis() {
        try {
            log.info("Force refreshing configuration from Redis");
            syncConfigFromRedis();
            return true;
        } catch (Exception e) {
            log.error("Failed to force refresh from Redis: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * BACKWARD COMPATIBILITY: Check if in sync with Redis
     */
    public boolean isInSyncWithRedis() {
        try {
            Optional<LocalDateTime> redisUpdateTime = redisStateService.getLastNginxUpdateTime();

            if (redisUpdateTime.isEmpty()) {
                return lastConfigUpdate == null;
            }

            if (lastConfigUpdate == null) {
                return false;
            }

            return !redisUpdateTime.get().isAfter(lastConfigUpdate);
        } catch (Exception e) {
            log.error("Error checking sync status: {}", e.getMessage(), e);
            return false;
        }
    }
}