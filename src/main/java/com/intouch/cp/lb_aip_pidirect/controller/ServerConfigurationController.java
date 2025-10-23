package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.model.ServerConfiguration;
import com.intouch.cp.lb_aip_pidirect.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/server-config")
@RequiredArgsConstructor
@Slf4j
public class ServerConfigurationController {

    private final ServerConfigurationService configService;
    private final WeightRecalculationService weightRecalculationService;
    private final MetricsCollectionService metricsCollectionService;
    private final NginxConfigService nginxConfigService;
    private final WeightCalculationService weightCalculationService;

    /**
     * Get configuration for a specific server
     */
    @GetMapping("/{serverId}")
    public ResponseEntity<ServerConfiguration> getServerConfig(@PathVariable String serverId) {
        log.debug("Fetching configuration for server: {}", serverId);

        return configService.getConfiguration(serverId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get all server configurations
     */
    @GetMapping
    public ResponseEntity<List<ServerConfiguration>> getAllConfigs() {
        log.debug("Fetching all server configurations");

        List<ServerConfiguration> configs = configService.getAllConfigurations();
        return ResponseEntity.ok(configs);
    }

    /**
     * Set fixed weight for a server (disable dynamic weight)
     */
    @PostMapping("/{serverId}/fixed-weight")
    public ResponseEntity<Map<String, Object>> setFixedWeight(
            @PathVariable String serverId,
            @RequestParam Integer weight) {

        log.info("Setting fixed weight {} for server: {}", weight, serverId);

        if (weight < 0 || weight > 100) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Weight must be between 0 and 100");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            ServerConfiguration config = configService.setFixedWeight(serverId, weight);

            // Trigger recalculation to apply fixed weight
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Fixed weight set successfully");
            response.put("serverId", serverId);
            response.put("weight", weight);
            response.put("dynamicWeightEnabled", false);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error setting fixed weight: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to set fixed weight: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Enable dynamic weight for a server
     */
    @PostMapping("/{serverId}/dynamic-weight")
    public ResponseEntity<Map<String, Object>> enableDynamicWeight(@PathVariable String serverId) {
        log.info("Enabling dynamic weight for server: {}", serverId);

        try {
            ServerConfiguration config = configService.enableDynamicWeight(serverId);

            // Trigger recalculation with dynamic weight
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Dynamic weight enabled successfully");
            response.put("serverId", serverId);
            response.put("dynamicWeightEnabled", true);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error enabling dynamic weight: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to enable dynamic weight: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Toggle between dynamic and fixed weight
     */
    @PostMapping("/{serverId}/toggle-weight-mode")
    public ResponseEntity<Map<String, Object>> toggleWeightMode(@PathVariable String serverId) {
        log.info("Toggling weight mode for server: {}", serverId);

        try {
            ServerConfiguration config = configService.getOrCreateConfiguration(serverId);

            Map<String, Object> response;

            if (config.getDynamicWeightEnabled()) {
                // Switch to fixed weight (default to 10 if not set)
                Integer fixedWeight = config.getFixedWeight() != null ?
                        config.getFixedWeight() : 10;
                config = configService.setFixedWeight(serverId, fixedWeight);

                response = new HashMap<>();
                response.put("message", "Switched to fixed weight mode");
                response.put("mode", "fixed");
                response.put("weight", fixedWeight);
            } else {
                // Switch to dynamic weight
                config = configService.enableDynamicWeight(serverId);

                response = new HashMap<>();
                response.put("message", "Switched to dynamic weight mode");
                response.put("mode", "dynamic");
            }

            // Trigger recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("serverId", serverId);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error toggling weight mode: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to toggle weight mode: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Set thresholds for automatic server removal
     */
    @PostMapping("/{serverId}/thresholds")
    public ResponseEntity<Map<String, Object>> setThresholds(
            @PathVariable String serverId,
            @RequestParam(required = false) Double maxResponseTime,
            @RequestParam(required = false) Double maxErrorRate,
            @RequestParam(required = false) Double minSuccessRate,
            @RequestParam(required = false) Double maxTimeoutRate,
            @RequestParam(required = false) Double minUptime) {

        log.info("Setting thresholds for server: {}", serverId);

        try {
            ServerConfiguration config = configService.setThresholds(
                    serverId, maxResponseTime, maxErrorRate, minSuccessRate,
                    maxTimeoutRate, minUptime);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Thresholds set successfully");
            response.put("serverId", serverId);
            response.put("thresholds", Map.of(
                    "maxResponseTimeMs", config.getMaxResponseTimeMs() != null ?
                            config.getMaxResponseTimeMs() : "not set",
                    "maxErrorRatePercentage", config.getMaxErrorRatePercentage() != null ?
                            config.getMaxErrorRatePercentage() : "not set",
                    "minSuccessRatePercentage", config.getMinSuccessRatePercentage() != null ?
                            config.getMinSuccessRatePercentage() : "not set",
                    "maxTimeoutRatePercentage", config.getMaxTimeoutRatePercentage() != null ?
                            config.getMaxTimeoutRatePercentage() : "not set",
                    "minUptimePercentage", config.getMinUptimePercentage() != null ?
                            config.getMinUptimePercentage() : "not set"
            ));
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error setting thresholds: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to set thresholds: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Enable auto-removal for a server
     */
    @PostMapping("/{serverId}/auto-removal/enable")
    public ResponseEntity<Map<String, Object>> enableAutoRemoval(
            @PathVariable String serverId,
            @RequestParam(required = false, defaultValue = "3") Integer maxViolations) {

        log.info("Enabling auto-removal for server: {} (max violations: {})",
                serverId, maxViolations);

        try {
            ServerConfiguration config = configService.enableAutoRemoval(serverId, maxViolations);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Auto-removal enabled successfully");
            response.put("serverId", serverId);
            response.put("autoRemovalEnabled", true);
            response.put("maxViolationsBeforeRemoval", maxViolations);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error enabling auto-removal: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to enable auto-removal: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Disable auto-removal for a server
     */
    @PostMapping("/{serverId}/auto-removal/disable")
    public ResponseEntity<Map<String, Object>> disableAutoRemoval(@PathVariable String serverId) {
        log.info("Disabling auto-removal for server: {}", serverId);

        try {
            ServerConfiguration config = configService.disableAutoRemoval(serverId);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Auto-removal disabled successfully");
            response.put("serverId", serverId);
            response.put("autoRemovalEnabled", false);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error disabling auto-removal: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to disable auto-removal: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Manually remove a server from load balancing
     */
    @PostMapping("/{serverId}/remove")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String serverId) {
        log.info("Manually removing server: {}", serverId);

        try {
            ServerConfiguration config = configService.manuallyRemoveServer(serverId);

            // Trigger recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Server removed from load balancing");
            response.put("serverId", serverId);
            response.put("manuallyRemoved", true);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error removing server: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to remove server: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Re-enable a manually removed server
     */
    @PostMapping("/{serverId}/enable")
    public ResponseEntity<Map<String, Object>> enableServer(@PathVariable String serverId) {
        log.info("Re-enabling server: {}", serverId);

        try {
            ServerConfiguration config = configService.reEnableServer(serverId);

            // Trigger recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Server re-enabled in load balancing");
            response.put("serverId", serverId);
            response.put("manuallyRemoved", false);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error enabling server: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to enable server: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Update full configuration for a server
     */
    @PutMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> updateConfiguration(
            @PathVariable String serverId,
            @RequestBody ServerConfiguration updates) {

        log.info("Updating configuration for server: {}", serverId);

        try {
            ServerConfiguration config = configService.updateConfiguration(serverId, updates);

            // Trigger recalculation if weight settings changed
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Configuration updated successfully");
            response.put("serverId", serverId);
            response.put("timestamp", LocalDateTime.now());
            response.put("configuration", config);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error updating configuration: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to update configuration: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Reset all configurations to defaults
     */
    @PostMapping("/reset-all")
    public ResponseEntity<Map<String, Object>> resetAllConfigurations() {
        log.info("Resetting all server configurations");

        try {
            configService.resetAllConfigurations();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "All server configurations reset to defaults");
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error resetting configurations: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to reset configurations: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Delete configuration for a server
     */
    @DeleteMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> deleteConfiguration(@PathVariable String serverId) {
        log.info("Deleting configuration for server: {}", serverId);

        try {
            configService.deleteConfiguration(serverId);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Configuration deleted successfully");
            response.put("serverId", serverId);
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting configuration: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to delete configuration: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}