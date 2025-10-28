package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.service.RedisStateService;
import com.intouch.cp.lb_aip_pidirect.service.RedisSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/redis")
@RequiredArgsConstructor
@Slf4j
public class RedisMonitoringController {

    private final RedisStateService redisStateService;
    private final RedisSyncService redisSyncService;

    @Value("${instance.id:default-instance}")
    private String instanceId;

    /**
     * Get Redis health status
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getRedisHealth() {
        Map<String, Object> response = new HashMap<>();
        
        boolean healthy = redisStateService.isRedisHealthy();
        response.put("healthy", healthy);
        response.put("instanceId", instanceId);
        response.put("timestamp", LocalDateTime.now());
        
        if (healthy) {
            return ResponseEntity.ok(response);
        } else {
            response.put("message", "Redis connection is not healthy");
            return ResponseEntity.status(503).body(response);
        }
    }

    /**
     * Get comprehensive Redis statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getRedisStats() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            response.put("instanceId", instanceId);
            response.put("timestamp", LocalDateTime.now());
            response.putAll(redisStateService.getRedisStats());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting Redis stats: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get all server metrics currently in Redis
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getAllMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, ServerMetrics> metrics = redisStateService.getAllServerMetrics();
            
            response.put("instanceId", instanceId);
            response.put("count", metrics.size());
            response.put("metrics", metrics);
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting metrics from Redis: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get metrics for a specific server from Redis
     */
    @GetMapping("/metrics/{serverId}")
    public ResponseEntity<Map<String, Object>> getServerMetrics(@PathVariable String serverId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            ServerMetrics metrics = redisStateService.getServerMetrics(serverId)
                    .orElse(null);
            
            response.put("instanceId", instanceId);
            response.put("serverId", serverId);
            response.put("found", metrics != null);
            
            if (metrics != null) {
                response.put("metrics", metrics);
            } else {
                response.put("message", "No metrics found for server " + serverId);
            }
            
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting metrics for server {}: {}", serverId, e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get all weight allocations from Redis
     */
    @GetMapping("/weights")
    public ResponseEntity<Map<String, Object>> getAllWeights() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<WeightAllocation> weights = redisStateService.getWeightAllocations();
            
            response.put("instanceId", instanceId);
            response.put("count", weights.size());
            response.put("weights", weights);
            response.put("timestamp", LocalDateTime.now());
            
            redisStateService.getLastWeightCalculationTime()
                    .ifPresent(time -> response.put("lastCalculation", time));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting weights from Redis: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get current Nginx configuration from Redis
     */
    @GetMapping("/nginx-config")
    public ResponseEntity<Map<String, Object>> getNginxConfig() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String config = redisStateService.getNginxConfig().orElse(null);
            
            response.put("instanceId", instanceId);
            response.put("found", config != null);
            
            if (config != null) {
                response.put("config", config);
            } else {
                response.put("message", "No Nginx configuration found in Redis");
            }
            
            redisStateService.getLastNginxUpdateTime()
                    .ifPresent(time -> response.put("lastUpdate", time));
            
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting Nginx config from Redis: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get sync service statistics
     */
    @GetMapping("/sync-stats")
    public ResponseEntity<RedisSyncService.SyncStats> getSyncStats() {
        try {
            return ResponseEntity.ok(redisSyncService.getSyncStats());
        } catch (Exception e) {
            log.error("Error getting sync stats: {}", e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Clear all data from Redis (for testing/reset)
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearRedisData() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            log.warn("[{}] Clearing all Redis data (requested via API)", instanceId);
            redisStateService.clearAllData();
            
            response.put("status", "success");
            response.put("message", "All load balancer data cleared from Redis");
            response.put("instanceId", instanceId);
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error clearing Redis data: {}", e.getMessage());
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Trigger manual cleanup of old metrics in Redis
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupOldMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            log.info("[{}] Manual cleanup triggered via API", instanceId);
            redisStateService.cleanupOldMetrics();
            
            response.put("status", "success");
            response.put("message", "Cleanup completed");
            response.put("instanceId", instanceId);
            response.put("timestamp", LocalDateTime.now());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during cleanup: {}", e.getMessage());
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get comprehensive dashboard data
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            response.put("instanceId", instanceId);
            response.put("timestamp", LocalDateTime.now());
            
            // Redis health
            response.put("redisHealthy", redisStateService.isRedisHealthy());
            
            // Metrics info
            Map<String, ServerMetrics> metrics = redisStateService.getAllServerMetrics();
            response.put("metricsCount", metrics.size());
            response.put("metrics", metrics);
            
            // Weights info
            List<WeightAllocation> weights = redisStateService.getWeightAllocations();
            response.put("weightsCount", weights.size());
            response.put("weights", weights);
            
            // Timestamps
            redisStateService.getLastWeightCalculationTime()
                    .ifPresent(time -> response.put("lastWeightCalculation", time));
            redisStateService.getLastNginxUpdateTime()
                    .ifPresent(time -> response.put("lastNginxUpdate", time));
            
            // Sync stats
            response.put("syncStats", redisSyncService.getSyncStats());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting dashboard data: {}", e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}