package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.util.NginxConfigGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
        log.info("=== Initializing NGINX Configuration Service ===");

        String configPath = nginxConfig.getNginx().getConfigPath();
        if (configPath == null || configPath.isEmpty()) {
            log.error("NGINX config path is not set. Please check configuration.");
            return;
        }

        log.info("NGINX config directory: {}", nginxConfig.getNginx().getConfigDir());
        log.info("NGINX config file: {}", nginxConfig.getNginx().getConfigFile());
        log.info("Full config path: {}", configPath);
        log.info("Dual upstream mode: {}", nginxConfig.isDualUpstreamEnabled() ? "ENABLED" : "DISABLED");

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
     * Update dual upstream configuration (incoming + outgoing)
     */
    public synchronized boolean updateDualUpstreamConfiguration(
            List<WeightAllocation> incomingWeights,
            List<WeightAllocation> outgoingWeights) {
        try {
            log.info("Updating DUAL upstream configuration - Incoming: {} servers, Outgoing: {} servers",
                    incomingWeights.size(), outgoingWeights.size());

            // Generate new dual configuration
            String newConfig = configGenerator.generateDualUpstreamConfig(incomingWeights, outgoingWeights);

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
     * Update single upstream configuration (backward compatibility)
     */
    public synchronized boolean updateUpstreamConfiguration(List<WeightAllocation> weights) {
        try {
            log.info("Updating upstream configuration with {} servers", weights.size());

            // Generate new configuration
            String newConfig = configGenerator.generateUpstreamConfig(weights);

            // Store in Redis for other instances to sync
            redisStateService.storeNginxConfig(newConfig);

            // Apply configuration locally
            return applyConfiguration(newConfig);

        } catch (Exception e) {
            log.error("Failed to update upstream configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Apply configuration to NGINX
     */
    private boolean applyConfiguration(String newConfig) {
        try {
            // Validate configuration
            if (!configGenerator.validateConfig(newConfig)) {
                log.error("Configuration validation failed. Skipping update.");
                return false;
            }

            String configPath = nginxConfig.getNginx().getConfigPath();
            Path path = Paths.get(configPath);

            // Backup current configuration
            if (Files.exists(path)) {
                Path backupPath = Paths.get(configPath + ".backup");
                Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Created backup: {}", backupPath);
            }

            // Write new configuration
            Files.writeString(path, newConfig);
            log.info("Written new configuration to: {}", configPath);

            // Set proper permissions
            try {
                Runtime.getRuntime().exec(new String[]{"chmod", "664", configPath});
            } catch (IOException e) {
                log.warn("Failed to set file permissions: {}", e.getMessage());
            }

            // Store current config
            currentConfigContent = newConfig;
            lastConfigUpdate = LocalDateTime.now();

            // Reload NGINX
            boolean reloaded = reloadNginx();
            if (reloaded) {
                log.info("✓ NGINX configuration updated and reloaded successfully");
                return true;
            } else {
                log.error("Failed to reload NGINX");
                return false;
            }

        } catch (IOException e) {
            log.error("Failed to write configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Periodically sync configuration from Redis
     * This ensures all instances have the same Nginx configuration
     */
    @Scheduled(fixedDelayString = "#{${loadbalancer.redis.intervals.config-sync:10000}}")
    public void syncConfigFromRedis() {
        try {
            // Get last update time from Redis
            Optional<LocalDateTime> redisUpdateTime = redisStateService.getLastNginxUpdateTime();

            if (redisUpdateTime.isEmpty()) {
                log.debug("No Redis configuration timestamp found");
                return;
            }

            // Check if we need to update
            if (lastConfigUpdate != null && !redisUpdateTime.get().isAfter(lastConfigUpdate)) {
                log.trace("Local config is up to date");
                return;
            }

            // Get config from Redis
            Optional<String> redisConfig = redisStateService.getNginxConfig();

            if (redisConfig.isEmpty()) {
                log.debug("No configuration found in Redis");
                return;
            }

            String newConfig = redisConfig.get();

            // Check if config actually changed
            if (newConfig.equals(currentConfigContent)) {
                log.trace("Config content unchanged, updating timestamp only");
                lastConfigUpdate = redisUpdateTime.get();
                return;
            }

            log.info("Syncing NGINX configuration from Redis (updated at: {})", redisUpdateTime.get());

            // Apply the configuration
            if (applyConfiguration(newConfig)) {
                log.info("✓ Successfully synced configuration from Redis");
            } else {
                log.error("Failed to apply configuration from Redis");
            }

        } catch (Exception e) {
            log.error("Error syncing config from Redis: {}", e.getMessage(), e);
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
                log.info("✓ NGINX reloaded successfully");
                return true;
            } else {
                log.error("NGINX reload failed with exit code: {}", exitCode);
                return false;
            }

        } catch (IOException | InterruptedException e) {
            log.error("Error reloading Nginx: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get current configuration content
     */
    public String getCurrentConfiguration() {
        if (currentConfigContent != null) {
            return currentConfigContent;
        }

        // Try to read from Redis
        Optional<String> redisConfig = redisStateService.getNginxConfig();
        if (redisConfig.isPresent()) {
            currentConfigContent = redisConfig.get();
            return currentConfigContent;
        }

        // Try to read from file
        try {
            String configPath = nginxConfig.getNginx().getConfigPath();
            Path path = Paths.get(configPath);

            if (Files.exists(path)) {
                currentConfigContent = Files.readString(path);
                return currentConfigContent;
            }
        } catch (IOException e) {
            log.error("Failed to read config from file: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * Force refresh configuration from Redis
     */
    public boolean forceRefreshFromRedis() {
        log.info("Forcing configuration refresh from Redis");
        lastConfigUpdate = null;
        syncConfigFromRedis();
        return currentConfigContent != null;
    }

    /**
     * Get last configuration update timestamp
     */
    public Optional<LocalDateTime> getLastUpdateTime() {
        return Optional.ofNullable(lastConfigUpdate);
    }

    /**
     * Check if Nginx configuration is in sync with Redis
     */
    public boolean isInSyncWithRedis() {
        try {
            Optional<LocalDateTime> redisTime = redisStateService.getLastNginxUpdateTime();

            if (redisTime.isEmpty() || lastConfigUpdate == null) {
                return false;
            }

            return !redisTime.get().isAfter(lastConfigUpdate);
        } catch (Exception e) {
            log.error("Error checking sync status: {}", e.getMessage());
            return false;
        }
    }
}