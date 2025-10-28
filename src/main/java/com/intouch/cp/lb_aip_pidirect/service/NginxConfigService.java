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
import java.util.List;
import java.util.Optional;

/**
 * Enhanced NginxConfigService with Redis synchronization
 * Periodically syncs configuration from Redis to ensure all instances are aligned
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
        log.info("=== Initializing NGINX Configuration Service with Redis Sync ===");

        // Verify configuration path
        String configPath = nginxConfig.getNginx().getConfigPath();
        if (configPath == null) {
            log.error("NGINX config path is not properly configured!");
            return;
        }

        log.info("NGINX config directory: {}", nginxConfig.getNginx().getConfigDir());
        log.info("NGINX config file: {}", nginxConfig.getNginx().getConfigFile());
        log.info("Full config path: {}", configPath);

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
     * Update upstream configuration and store in Redis
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
     * Periodically sync configuration from Redis
     * This ensures all instances have the same Nginx configuration
     */
    @Scheduled(fixedDelayString = "#{${loadbalancer.redis.intervals.config-sync:10000}}")
    public void syncConfigFromRedis() {
        try {
            // Get last update time from Redis
            Optional<LocalDateTime> redisUpdateTime = redisStateService.getLastNginxUpdateTime();

            if (redisUpdateTime.isEmpty()) {
                log.debug("No configuration update timestamp in Redis");
                return;
            }

            // Check if Redis has a newer configuration
            if (lastConfigUpdate == null || redisUpdateTime.get().isAfter(lastConfigUpdate)) {
                log.info("Detected newer configuration in Redis, syncing...");

                Optional<String> redisConfig = redisStateService.getNginxConfig();
                if (redisConfig.isPresent()) {
                    String config = redisConfig.get();

                    // Only apply if different from current
                    if (!config.equals(currentConfigContent)) {
                        if (applyConfiguration(config)) {
                            lastConfigUpdate = redisUpdateTime.get();
                            currentConfigContent = config;
                            log.info("Successfully synced configuration from Redis");
                        }
                    } else {
                        log.debug("Configuration already up to date");
                        lastConfigUpdate = redisUpdateTime.get();
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error syncing configuration from Redis: {}", e.getMessage(), e);
        }
    }

    /**
     * Apply configuration to Nginx
     */
    private boolean applyConfiguration(String config) {
        try {
            // Write to file
            if (!writeConfigToFile(config)) {
                return false;
            }

            // Reload Nginx
            boolean reloadSuccess = reloadNginx();

            if (reloadSuccess) {
                currentConfigContent = config;
                lastConfigUpdate = LocalDateTime.now();
                log.info("Configuration applied and Nginx reloaded successfully");
            }

            return reloadSuccess;

        } catch (Exception e) {
            log.error("Failed to apply configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Write configuration to file
     */
    private boolean writeConfigToFile(String config) {
        String configPath = nginxConfig.getNginx().getConfigPath();

        try {
            // Ensure parent directory exists
            Path path = Paths.get(configPath);
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // Write configuration
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(configPath))) {
                writer.write(config);
            }

            log.info("Configuration written to: {}", configPath);
            return true;

        } catch (IOException e) {
            log.error("Failed to write config to file {}: {}", configPath, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reload Nginx configuration
     */
    private boolean reloadNginx() {
        try {
            String reloadCommand = nginxConfig.getNginx().getReloadCommand();

            if (reloadCommand == null || reloadCommand.isEmpty()) {
                log.warn("No reload command configured, skipping Nginx reload");
                return true;
            }

            log.info("Executing reload command: {}", reloadCommand);

            ProcessBuilder processBuilder = new ProcessBuilder(reloadCommand.split(" "));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("Nginx reloaded successfully");
                return true;
            } else {
                log.error("Nginx reload failed with exit code: {}", exitCode);
                return false;
            }

        } catch (Exception e) {
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