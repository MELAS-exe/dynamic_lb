package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import com.intouch.cp.lb_aip_pidirect.service.WeightRecalculationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@Slf4j
public class DebugController {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private MetricsCollectionService metricsCollectionService;

    @Autowired(required = false)
    private WeightRecalculationService weightRecalculationService;

    /**
     * Debug endpoint to check loaded beans
     */
    @GetMapping("/beans")
    public ResponseEntity<Map<String, Object>> checkBeans() {
        Map<String, Object> response = new HashMap<>();
        
        boolean hasServerConfigService = applicationContext.containsBean("serverConfigurationService");
        boolean hasServerConfigRepo = applicationContext.containsBean("serverConfigurationRepository");
        boolean hasWeightFactorsService = applicationContext.containsBean("weightFactorsService");
        boolean hasWeightRecalcService = applicationContext.containsBean("weightRecalculationService");
        boolean hasMetricsService = applicationContext.containsBean("metricsCollectionService");
        
        response.put("serverConfigurationService", hasServerConfigService);
        response.put("serverConfigurationRepository", hasServerConfigRepo);
        response.put("weightFactorsService", hasWeightFactorsService);
        response.put("weightRecalculationService", hasWeightRecalcService);
        response.put("metricsCollectionService", hasMetricsService);
        
        String[] allBeans = applicationContext.getBeanDefinitionNames();
        response.put("configBeanNames", Arrays.stream(allBeans)
                .filter(bean -> bean.toLowerCase().contains("config") || 
                               bean.toLowerCase().contains("weight") ||
                               bean.toLowerCase().contains("metrics"))
                .toList());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Test endpoint
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> test() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Debug controller is working");
        response.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Check NGINX config file
     */
    @GetMapping("/nginx-config")
    public ResponseEntity<Map<String, Object>> checkNginxConfig() {
        Map<String, Object> response = new HashMap<>();
        
        String configPath = "/nginx-config/upstream.conf";
        Path path = Paths.get(configPath);
        
        response.put("configPath", configPath);
        response.put("fileExists", Files.exists(path));
        
        if (Files.exists(path)) {
            try {
                response.put("fileSize", Files.size(path));
                response.put("lastModified", Files.getLastModifiedTime(path).toString());
                response.put("content", Files.readString(path));
            } catch (IOException e) {
                response.put("error", "Cannot read file: " + e.getMessage());
            }
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Manually trigger weight recalculation
     */
    @PostMapping("/trigger-recalculation")
    public ResponseEntity<Map<String, Object>> triggerRecalculation() {
        Map<String, Object> response = new HashMap<>();
        
        if (weightRecalculationService == null) {
            response.put("status", "error");
            response.put("message", "WeightRecalculationService not available");
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            log.info("Manually triggering weight recalculation");
            weightRecalculationService.triggerWeightRecalculation();
            
            response.put("status", "success");
            response.put("message", "Weight recalculation triggered");
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Manually trigger metrics processing
     */
    @PostMapping("/trigger-metrics-processing")
    public ResponseEntity<Map<String, Object>> triggerMetricsProcessing() {
        Map<String, Object> response = new HashMap<>();
        
        if (metricsCollectionService == null) {
            response.put("status", "error");
            response.put("message", "MetricsCollectionService not available");
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            log.info("Manually triggering metrics processing");
            metricsCollectionService.processMetricsAndUpdateWeights();
            
            response.put("status", "success");
            response.put("message", "Metrics processing triggered");
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Error: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Get scheduling info
     */
    @GetMapping("/scheduling-info")
    public ResponseEntity<Map<String, Object>> getSchedulingInfo() {
        Map<String, Object> response = new HashMap<>();
        
        response.put("timestamp", LocalDateTime.now());
        response.put("metricsServiceAvailable", metricsCollectionService != null);
        response.put("weightRecalcServiceAvailable", weightRecalculationService != null);
        
        return ResponseEntity.ok(response);
    }
}