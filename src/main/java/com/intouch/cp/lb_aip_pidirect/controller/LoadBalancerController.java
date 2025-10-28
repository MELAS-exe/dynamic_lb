package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.service.*;
import com.intouch.cp.lb_aip_pidirect.util.NginxConfigGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced LoadBalancerController with Redis state management endpoints
 */
@RestController
@RequestMapping("/api/loadbalancer")
@RequiredArgsConstructor
@Slf4j
public class LoadBalancerController {

    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;
    private final MetricsCollectionService metricsCollectionService;
    private final NginxConfigGenerator configGenerator;
    private final NginxConfig nginxConfig;
    private final RedisStateService redisStateService;
    private final InstanceHeartbeatService heartbeatService;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("instance", heartbeatService.getInstanceId());
        health.put("timestamp", LocalDateTime.now());
        health.put("redisHealthy", redisStateService.isHealthy());

        return ResponseEntity.ok(health);
    }

    /**
     * Get current weight allocations from Redis
     */
    @GetMapping("/weights")
    public ResponseEntity<Map<String, Object>> getCurrentWeights() {
        log.debug("Fetching current weight allocations");

        Map<String, Object> response = new HashMap<>();

        // Try to get weights from Redis first
        var redisWeights = redisStateService.getWeights();

        if (redisWeights.isPresent()) {
            response.put("source", "redis");
            response.put("weights", redisWeights.get());
        } else {
            // Calculate fresh weights if not in Redis
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);
            response.put("source", "calculated");
            response.put("weights", weights);
        }

        response.put("instance", heartbeatService.getInstanceId());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Force recalculation of weights and update NGINX
     */
    @PostMapping("/weights/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateWeights() {
        log.info("Manual weight recalculation triggered by instance: {}",
                heartbeatService.getInstanceId());

        try {
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();

            if (latestMetrics.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "warning");
                response.put("message", "No metrics available for weight calculation");
                response.put("timestamp", LocalDateTime.now());
                response.put("instance", heartbeatService.getInstanceId());
                return ResponseEntity.ok(response);
            }

            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);

            // Store in Redis
            redisStateService.storeWeights(weights);

            // Update local Nginx
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Weights recalculated and stored in Redis");
            response.put("timestamp", LocalDateTime.now());
            response.put("instance", heartbeatService.getInstanceId());
            response.put("weightsCalculated", weights.size());
            response.put("activeServers", weights.stream().mapToInt(w -> w.isActive() ? 1 : 0).sum());
            response.put("weights", weights);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error during manual weight recalculation: {}", e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Failed to recalculate weights: " + e.getMessage());
            response.put("timestamp", LocalDateTime.now());
            response.put("instance", heartbeatService.getInstanceId());

            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Get current NGINX configuration
     */
    @GetMapping(value = "/nginx/config", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getCurrentNginxConfig() {
        log.debug("Fetching current NGINX configuration");

        String config = nginxConfigService.getCurrentConfiguration();

        if (config != null) {
            return ResponseEntity.ok(config);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Force sync configuration from Redis
     */
    @PostMapping("/nginx/sync")
    public ResponseEntity<Map<String, Object>> syncFromRedis() {
        log.info("Manual configuration sync from Redis triggered by instance: {}",
                heartbeatService.getInstanceId());

        Map<String, Object> response = new HashMap<>();

        boolean success = nginxConfigService.forceRefreshFromRedis();

        response.put("status", success ? "success" : "error");
        response.put("message", success ? "Configuration synced from Redis" : "Failed to sync configuration");
        response.put("timestamp", LocalDateTime.now());
        response.put("instance", heartbeatService.getInstanceId());
        response.put("inSync", nginxConfigService.isInSyncWithRedis());

        return ResponseEntity.ok(response);
    }

    /**
     * Get Redis state information
     */
    @GetMapping("/redis/state")
    public ResponseEntity<Map<String, Object>> getRedisState() {
        log.debug("Fetching Redis state information");

        Map<String, Object> state = new HashMap<>();

        state.put("healthy", redisStateService.isHealthy());
        state.put("activeInstances", redisStateService.getActiveInstances());
        state.put("currentInstance", heartbeatService.getInstanceId());
        state.put("weightsAvailable", redisStateService.getWeights().isPresent());
        state.put("configAvailable", redisStateService.getNginxConfig().isPresent());
        state.put("lastConfigUpdate", redisStateService.getLastNginxUpdateTime().orElse(null));
        state.put("localConfigSync", nginxConfigService.isInSyncWithRedis());
        state.put("timestamp", LocalDateTime.now());

        // Get metrics count
        Map<String, ServerMetrics> metrics = redisStateService.getAllMetrics();
        state.put("metricsInRedis", metrics.size());

        return ResponseEntity.ok(state);
    }

    /**
     * Get instance information
     */
    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> getInstancesInfo() {
        Map<String, Object> info = new HashMap<>();

        info.put("currentInstance", heartbeatService.getInstanceId());
        info.put("activeInstances", heartbeatService.getActiveInstances());
        info.put("instanceCount", heartbeatService.getActiveInstances().size());
        info.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(info);
    }

    /**
     * Get load balancer status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        log.debug("Fetching load balancer status");

        Map<String, Object> status = new HashMap<>();
        status.put("instance", heartbeatService.getInstanceId());
        status.put("activeInstances", heartbeatService.getActiveInstances().size());
        status.put("serverCount", nginxConfig.getServerCount());
        status.put("configPath", nginxConfig.getNginx().getConfigPath());
        status.put("upstreamName", nginxConfig.getNginx().getUpstreamName());
        status.put("redisHealthy", redisStateService.isHealthy());
        status.put("configInSync", nginxConfigService.isInSyncWithRedis());
        status.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(status);
    }

    /**
     * Get load balancer configuration
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getLoadBalancerConfig() {
        log.debug("Fetching load balancer configuration");

        Map<String, Object> config = new HashMap<>();
        config.put("servers", nginxConfig.getServers());
        config.put("nginx", nginxConfig.getNginx());
        config.put("weightFactors", nginxConfig.getWeightFactors());
        config.put("simulation", nginxConfig.getSimulation());
        config.put("instance", heartbeatService.getInstanceId());

        return ResponseEntity.ok(config);
    }

    /**
     * Validate weight factors configuration
     */
    @GetMapping("/config/validate")
    public ResponseEntity<Map<String, Object>> validateConfiguration() {
        log.debug("Validating load balancer configuration");

        Map<String, Object> validation = new HashMap<>();
        validation.put("timestamp", LocalDateTime.now());
        validation.put("instance", heartbeatService.getInstanceId());

        // Validate weight factors
        boolean factorsValid = nginxConfig.hasValidWeightFactors();
        validation.put("weightFactorsValid", factorsValid);

        if (!factorsValid && nginxConfig.getWeightFactors() != null) {
            validation.put("weightFactorsSum", nginxConfig.getWeightFactors().getSum());
            validation.put("expectedSum", 1.0);
        }

        // Validate servers
        validation.put("serverCount", nginxConfig.getServerCount());
        validation.put("servers", nginxConfig.getServers());

        // Check for duplicate server IDs
        long uniqueServerIds = nginxConfig.getServers().stream()
                .map(s -> s.getId())
                .distinct()
                .count();
        validation.put("hasUniqueServerIds", uniqueServerIds == nginxConfig.getServerCount());

        // Overall validity
        boolean overallValid = factorsValid && uniqueServerIds == nginxConfig.getServerCount();
        validation.put("configurationValid", overallValid);

        return ResponseEntity.ok(validation);
    }

    /**
     * Get metrics for all servers
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getAllMetrics() {
        Map<String, Object> response = new HashMap<>();

        Map<String, ServerMetrics> metrics = redisStateService.getAllMetrics();

        response.put("metrics", metrics);
        response.put("serverCount", metrics.size());
        response.put("instance", heartbeatService.getInstanceId());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get metrics for a specific server
     */
    @GetMapping("/metrics/{serverId}")
    public ResponseEntity<Map<String, Object>> getServerMetrics(@PathVariable String serverId) {
        Map<String, Object> response = new HashMap<>();

        var metrics = metricsCollectionService.getServerMetrics(serverId);

        if (metrics.isPresent()) {
            response.put("metrics", metrics.get());
            response.put("found", true);
        } else {
            response.put("found", false);
            response.put("message", "No metrics found for server: " + serverId);
        }

        response.put("serverId", serverId);
        response.put("instance", heartbeatService.getInstanceId());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }
}