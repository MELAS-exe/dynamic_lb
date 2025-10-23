package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeightFactorsService {

    private final NginxConfig nginxConfig;

    /**
     * Get current weight factors
     */
    public Map<String, Double> getWeightFactors() {
        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();
        
        Map<String, Double> result = new HashMap<>();
        result.put("responseTime", factors.getResponseTime());
        result.put("errorRate", factors.getErrorRate());
        result.put("timeoutRate", factors.getTimeoutRate());
        result.put("uptime", factors.getUptime());
        result.put("degradation", factors.getDegradation());
        result.put("sum", factors.getSum());
        result.put("isValid", factors.isValid() ? 1.0 : 0.0);
        
        return result;
    }

    /**
     * Update weight factors
     */
    public Map<String, Object> updateWeightFactors(Double responseTime,
                                                   Double errorRate,
                                                   Double timeoutRate,
                                                   Double uptime,
                                                   Double degradation) {
        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();

        // Store old values for logging
        double oldResponseTime = factors.getResponseTime();
        double oldErrorRate = factors.getErrorRate();
        double oldTimeoutRate = factors.getTimeoutRate();
        double oldUptime = factors.getUptime();
        double oldDegradation = factors.getDegradation();

        // Update factors if provided
        if (responseTime != null) {
            factors.setResponseTime(responseTime);
        }
        if (errorRate != null) {
            factors.setErrorRate(errorRate);
        }
        if (timeoutRate != null) {
            factors.setTimeoutRate(timeoutRate);
        }
        if (uptime != null) {
            factors.setUptime(uptime);
        }
        if (degradation != null) {
            factors.setDegradation(degradation);
        }

        // Validate
        boolean valid = factors.isValid();
        double sum = factors.getSum();

        Map<String, Object> result = new HashMap<>();
        result.put("status", valid ? "success" : "warning");
        result.put("message", valid ? 
            "Weight factors updated successfully" : 
            String.format("Warning: Weight factors sum to %.4f instead of 1.0", sum));
        
        result.put("oldFactors", Map.of(
            "responseTime", oldResponseTime,
            "errorRate", oldErrorRate,
            "timeoutRate", oldTimeoutRate,
            "uptime", oldUptime,
            "degradation", oldDegradation,
            "sum", oldResponseTime + oldErrorRate + oldTimeoutRate + oldUptime + oldDegradation
        ));
        
        result.put("newFactors", Map.of(
            "responseTime", factors.getResponseTime(),
            "errorRate", factors.getErrorRate(),
            "timeoutRate", factors.getTimeoutRate(),
            "uptime", factors.getUptime(),
            "degradation", factors.getDegradation(),
            "sum", sum
        ));
        
        result.put("isValid", valid);

        log.info("Weight factors updated. Old sum: {}, New sum: {}, Valid: {}", 
                oldResponseTime + oldErrorRate + oldTimeoutRate + oldUptime + oldDegradation,
                sum, 
                valid);

        return result;
    }

    /**
     * Update a single weight factor
     */
    public Map<String, Object> updateSingleFactor(String factorName, Double value) {
        if (value == null || value < 0 || value > 1) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Factor value must be between 0 and 1");
            return error;
        }

        return switch (factorName.toLowerCase()) {
            case "responsetime", "response-time", "response_time" -> 
                updateWeightFactors(value, null, null, null, null);
            case "errorrate", "error-rate", "error_rate" -> 
                updateWeightFactors(null, value, null, null, null);
            case "timeoutrate", "timeout-rate", "timeout_rate" -> 
                updateWeightFactors(null, null, value, null, null);
            case "uptime" -> 
                updateWeightFactors(null, null, null, value, null);
            case "degradation" -> 
                updateWeightFactors(null, null, null, null, value);
            default -> {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "Unknown factor name: " + factorName);
                yield error;
            }
        };
    }

    /**
     * Normalize weight factors to sum to 1.0
     */
    public Map<String, Object> normalizeWeightFactors() {
        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();
        
        double currentSum = factors.getSum();
        
        if (currentSum == 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Cannot normalize: all factors are zero");
            return error;
        }

        // Store old values
        double oldResponseTime = factors.getResponseTime();
        double oldErrorRate = factors.getErrorRate();
        double oldTimeoutRate = factors.getTimeoutRate();
        double oldUptime = factors.getUptime();
        double oldDegradation = factors.getDegradation();

        // Normalize
        factors.setResponseTime(factors.getResponseTime() / currentSum);
        factors.setErrorRate(factors.getErrorRate() / currentSum);
        factors.setTimeoutRate(factors.getTimeoutRate() / currentSum);
        factors.setUptime(factors.getUptime() / currentSum);
        factors.setDegradation(factors.getDegradation() / currentSum);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Weight factors normalized to sum to 1.0");
        result.put("oldSum", currentSum);
        result.put("newSum", factors.getSum());
        
        result.put("oldFactors", Map.of(
            "responseTime", oldResponseTime,
            "errorRate", oldErrorRate,
            "timeoutRate", oldTimeoutRate,
            "uptime", oldUptime,
            "degradation", oldDegradation
        ));
        
        result.put("newFactors", Map.of(
            "responseTime", factors.getResponseTime(),
            "errorRate", factors.getErrorRate(),
            "timeoutRate", factors.getTimeoutRate(),
            "uptime", factors.getUptime(),
            "degradation", factors.getDegradation()
        ));

        log.info("Weight factors normalized from sum {} to {}", currentSum, factors.getSum());

        return result;
    }

    /**
     * Reset weight factors to default values
     */
    public Map<String, Object> resetWeightFactors() {
        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();

        // Default values that sum to 1.0
        factors.setResponseTime(0.25);
        factors.setErrorRate(0.25);
        factors.setTimeoutRate(0.15);
        factors.setUptime(0.20);
        factors.setDegradation(0.15);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Weight factors reset to default values");
        result.put("factors", Map.of(
            "responseTime", 0.25,
            "errorRate", 0.25,
            "timeoutRate", 0.15,
            "uptime", 0.20,
            "degradation", 0.15,
            "sum", 1.0
        ));

        log.info("Weight factors reset to defaults");

        return result;
    }

    /**
     * Validate current weight factors
     */
    public Map<String, Object> validateWeightFactors() {
        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();
        
        boolean valid = factors.isValid();
        double sum = factors.getSum();

        Map<String, Object> result = new HashMap<>();
        result.put("isValid", valid);
        result.put("sum", sum);
        result.put("expectedSum", 1.0);
        result.put("difference", Math.abs(sum - 1.0));
        result.put("tolerance", 0.01);
        
        if (valid) {
            result.put("message", "Weight factors are valid");
        } else {
            result.put("message", String.format(
                "Weight factors sum to %.4f (expected 1.0 ± 0.01)", sum));
        }

        result.put("factors", Map.of(
            "responseTime", factors.getResponseTime(),
            "errorRate", factors.getErrorRate(),
            "timeoutRate", factors.getTimeoutRate(),
            "uptime", factors.getUptime(),
            "degradation", factors.getDegradation()
        ));

        return result;
    }
}