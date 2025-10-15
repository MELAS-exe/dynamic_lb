package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import com.intouch.cp.lb_aip_pidirect.service.NginxConfigService;
import com.intouch.cp.lb_aip_pidirect.service.WeightCalculationService;
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

    /**
     * Get current weight allocations
     */
    @GetMapping("/weights")
    public ResponseEntity<List<WeightAllocation>> getCurrentWeights() {
        log.debug("Fetching current weight allocations");

        List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
        List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);

        return ResponseEntity.ok(weights);
    }

    /**
     * Force recalculation of weights and update NGINX
     */
    @PostMapping("/weights/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateWeights() {
        log.info("Manual weight recalculation triggered");

        try {
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();

            if (latestMetrics.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "warning");
                response.put("message", "No metrics available for weight calculation");
                response.put("timestamp", LocalDateTime.now());
                return ResponseEntity.ok(response);
            }

            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Weights recalculated and NGINX updated");
            response.put("timestamp", LocalDateTime.now());
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
     * Generate and preview NGINX configuration without applying it
     */
    @GetMapping(value = "/nginx/config/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> previewNginxConfig() {
        log.debug("Generating preview of NGINX configuration");

        try {
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);
            String config = configGenerator.generateUpstreamConfig(weights);

            return ResponseEntity.ok(config);

        } catch (Exception e) {
            log.error("Error generating configuration preview: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Error generating configuration: " + e.getMessage());
        }
    }

    /**
     * Get configuration summary
     */
    @GetMapping("/nginx/config/summary")
    public ResponseEntity<String> getConfigSummary() {
        log.debug("Generating configuration summary");

        try {
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);
            String summary = configGenerator.generateConfigSummary(weights);

            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Error generating configuration summary: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Error generating summary: " + e.getMessage());
        }
    }

    /**
     * Check NGINX status
     */
    @GetMapping("/nginx/status")
    public ResponseEntity<Map<String, Object>> getNginxStatus() {
        log.debug("Checking NGINX status");

        Map<String, Object> status = new HashMap<>();
        status.put("isRunning", nginxConfigService.isNginxRunning());
        status.put("configPath", nginxConfig.getNginx().getConfigPath());
        status.put("upstreamName", nginxConfig.getNginx().getUpstreamName());
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
     * Test NGINX configuration without applying
     */
    @PostMapping("/nginx/test")
    public ResponseEntity<Map<String, Object>> testNginxConfiguration() {
        log.info("Testing NGINX configuration");

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate current configuration
            List<ServerMetrics> latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            List<WeightAllocation> weights = weightCalculationService.calculateWeights(latestMetrics);
            String config = configGenerator.generateUpstreamConfig(weights);

            // Validate generated config
            boolean valid = configGenerator.validateGeneratedConfig(config);

            result.put("status", valid ? "success" : "error");
            result.put("configurationValid", valid);
            result.put("timestamp", LocalDateTime.now());
            result.put("serversInConfig", weights.size());
            result.put("activeServers", weights.stream().mapToInt(w -> w.isActive() ? 1 : 0).sum());

            if (valid) {
                result.put("message", "Configuration is valid");
            } else {
                result.put("message", "Configuration validation failed");
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error testing NGINX configuration: {}", e.getMessage());

            result.put("status", "error");
            result.put("message", "Error testing configuration: " + e.getMessage());
            result.put("timestamp", LocalDateTime.now());

            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * Get system health status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("timestamp", LocalDateTime.now());
        health.put("service", "load-balancer");

        try {
            // Check if we have recent metrics
            List<ServerMetrics> metrics = metricsCollectionService.getLatestMetricsForAllServers();
            health.put("metricsAvailable", !metrics.isEmpty());
            health.put("serverCount", nginxConfig.getServerCount());
            health.put("metricsCount", metrics.size());

            // Check NGINX status
            health.put("nginxRunning", nginxConfigService.isNginxRunning());

            // Check configuration validity
            health.put("configValid", nginxConfig.hasValidWeightFactors());

            // Overall health
            boolean healthy = !metrics.isEmpty() && nginxConfig.hasValidWeightFactors();
            health.put("status", healthy ? "healthy" : "degraded");

        } catch (Exception e) {
            health.put("status", "error");
            health.put("error", e.getMessage());
        }

        return ResponseEntity.ok(health);
    }
}