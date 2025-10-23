package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.service.WeightFactorsService;
import com.intouch.cp.lb_aip_pidirect.service.WeightCalculationService;
import com.intouch.cp.lb_aip_pidirect.service.NginxConfigService;
import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/weight-factors")
@RequiredArgsConstructor
@Slf4j
public class WeightFactorsController {

    private final WeightFactorsService weightFactorsService;
    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;
    private final MetricsCollectionService metricsCollectionService;

    /**
     * Get current weight factors
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getWeightFactors() {
        log.debug("Fetching current weight factors");

        Map<String, Double> factors = weightFactorsService.getWeightFactors();
        
        Map<String, Object> response = new HashMap<>();
        response.put("factors", factors);
        response.put("timestamp", LocalDateTime.now());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Update all weight factors at once
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateAllWeightFactors(
            @RequestParam(required = false) Double responseTime,
            @RequestParam(required = false) Double errorRate,
            @RequestParam(required = false) Double timeoutRate,
            @RequestParam(required = false) Double uptime,
            @RequestParam(required = false) Double degradation) {
        
        log.info("Updating weight factors");

        try {
            Map<String, Object> result = weightFactorsService.updateWeightFactors(
                responseTime, errorRate, timeoutRate, uptime, degradation);

            // Trigger weight recalculation with new factors
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            result.put("timestamp", LocalDateTime.now());
            result.put("weightsRecalculated", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error updating weight factors: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to update weight factors: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Update a single weight factor
     */
    @PutMapping("/{factorName}")
    public ResponseEntity<Map<String, Object>> updateSingleFactor(
            @PathVariable String factorName,
            @RequestParam Double value) {
        
        log.info("Updating weight factor '{}' to {}", factorName, value);

        try {
            Map<String, Object> result = weightFactorsService.updateSingleFactor(factorName, value);

            if ("error".equals(result.get("status"))) {
                return ResponseEntity.badRequest().body(result);
            }

            // Trigger weight recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            result.put("timestamp", LocalDateTime.now());
            result.put("weightsRecalculated", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error updating weight factor: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to update weight factor: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Normalize weight factors to sum to 1.0
     */
    @PostMapping("/normalize")
    public ResponseEntity<Map<String, Object>> normalizeWeightFactors() {
        log.info("Normalizing weight factors");

        try {
            Map<String, Object> result = weightFactorsService.normalizeWeightFactors();

            if ("error".equals(result.get("status"))) {
                return ResponseEntity.badRequest().body(result);
            }

            // Trigger weight recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            result.put("timestamp", LocalDateTime.now());
            result.put("weightsRecalculated", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error normalizing weight factors: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to normalize weight factors: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Reset weight factors to default values
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetWeightFactors() {
        log.info("Resetting weight factors to defaults");

        try {
            Map<String, Object> result = weightFactorsService.resetWeightFactors();

            // Trigger weight recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            result.put("timestamp", LocalDateTime.now());
            result.put("weightsRecalculated", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error resetting weight factors: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to reset weight factors: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Validate current weight factors
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateWeightFactors() {
        log.debug("Validating weight factors");

        Map<String, Object> result = weightFactorsService.validateWeightFactors();
        result.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(result);
    }

    /**
     * Get weight factor presets
     */
    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getPresets() {
        log.debug("Fetching weight factor presets");

        Map<String, Object> response = new HashMap<>();
        
        // Balanced preset
        Map<String, Double> balanced = new HashMap<>();
        balanced.put("responseTime", 0.25);
        balanced.put("errorRate", 0.25);
        balanced.put("timeoutRate", 0.15);
        balanced.put("uptime", 0.20);
        balanced.put("degradation", 0.15);
        
        // Performance-focused preset
        Map<String, Double> performance = new HashMap<>();
        performance.put("responseTime", 0.40);
        performance.put("errorRate", 0.20);
        performance.put("timeoutRate", 0.10);
        performance.put("uptime", 0.15);
        performance.put("degradation", 0.15);
        
        // Reliability-focused preset
        Map<String, Double> reliability = new HashMap<>();
        reliability.put("responseTime", 0.15);
        reliability.put("errorRate", 0.30);
        reliability.put("timeoutRate", 0.20);
        reliability.put("uptime", 0.30);
        reliability.put("degradation", 0.05);
        
        // Error-avoidance preset
        Map<String, Double> errorAvoidance = new HashMap<>();
        errorAvoidance.put("responseTime", 0.15);
        errorAvoidance.put("errorRate", 0.40);
        errorAvoidance.put("timeoutRate", 0.25);
        errorAvoidance.put("uptime", 0.15);
        errorAvoidance.put("degradation", 0.05);

        response.put("presets", Map.of(
            "balanced", balanced,
            "performance", performance,
            "reliability", reliability,
            "errorAvoidance", errorAvoidance
        ));
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Apply a preset configuration
     */
    @PostMapping("/presets/{presetName}")
    public ResponseEntity<Map<String, Object>> applyPreset(@PathVariable String presetName) {
        log.info("Applying preset: {}", presetName);

        try {
            Map<String, Object> result;

            switch (presetName.toLowerCase()) {
                case "balanced":
                    result = weightFactorsService.updateWeightFactors(
                        0.25, 0.25, 0.15, 0.20, 0.15);
                    break;

                case "performance":
                    result = weightFactorsService.updateWeightFactors(
                        0.40, 0.20, 0.10, 0.15, 0.15);
                    break;

                case "reliability":
                    result = weightFactorsService.updateWeightFactors(
                        0.15, 0.30, 0.20, 0.30, 0.05);
                    break;

                case "erroravoidance", "error-avoidance":
                    result = weightFactorsService.updateWeightFactors(
                        0.15, 0.40, 0.25, 0.15, 0.05);
                    break;

                default:
                    Map<String, Object> error = new HashMap<>();
                    error.put("status", "error");
                    error.put("message", "Unknown preset: " + presetName);
                    error.put("availablePresets", List.of(
                        "balanced", "performance", "reliability", "errorAvoidance"));
                    return ResponseEntity.badRequest().body(error);
            }

            // Trigger weight recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            result.put("presetApplied", presetName);
            result.put("timestamp", LocalDateTime.now());
            result.put("weightsRecalculated", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error applying preset: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to apply preset: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get impact analysis of changing a factor
     */
    @GetMapping("/impact-analysis")
    public ResponseEntity<Map<String, Object>> getImpactAnalysis(
            @RequestParam String factorName,
            @RequestParam Double newValue) {
        
        log.debug("Analyzing impact of changing {} to {}", factorName, newValue);

        try {
            // Get current factors
            Map<String, Double> currentFactors = weightFactorsService.getWeightFactors();
            double currentValue = currentFactors.getOrDefault(
                factorName.toLowerCase().replace("-", "").replace("_", ""), 0.0);

            // Calculate impact
            double change = newValue - currentValue;
            double percentChange = currentValue > 0 ? (change / currentValue) * 100 : 0;

            Map<String, Object> response = new HashMap<>();
            response.put("factorName", factorName);
            response.put("currentValue", currentValue);
            response.put("newValue", newValue);
            response.put("absoluteChange", change);
            response.put("percentChange", percentChange);
            response.put("impact", Math.abs(percentChange) > 50 ? "high" : 
                                  Math.abs(percentChange) > 20 ? "medium" : "low");
            response.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error analyzing impact: {}", e.getMessage());
            
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to analyze impact: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
}