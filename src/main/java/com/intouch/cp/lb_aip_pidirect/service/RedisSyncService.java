package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisSyncService {

    private final RedisStateService redisStateService;
    private final NginxConfigService nginxConfigService;
    private final NginxConfig nginxConfig;

    @Value("${instance.id:default-instance}")
    private String instanceId;

    private LocalDateTime lastSyncTime;
    private int syncCount = 0;
    private int successfulSyncs = 0;
    private int failedSyncs = 0;

    @PostConstruct
    public void init() {
        log.info("=== Redis Sync Service Initialized ===");
        log.info("Instance ID: {}", instanceId);
        log.info("Sync interval: {}ms", nginxConfig.getRedis().getIntervals().getConfigSync());
        
        // Perform initial sync check
        performInitialSync();
    }

    /**
     * Perform initial sync on startup
     */
    private void performInitialSync() {
        try {
            log.info("Performing initial sync check...");
            
            if (!redisStateService.isRedisHealthy()) {
                log.warn("Redis is not healthy during initial sync. Will retry on scheduled sync.");
                return;
            }

            // Check if there are existing weight allocations in Redis
            List<WeightAllocation> weights = redisStateService.getWeightAllocations();
            
            if (!weights.isEmpty()) {
                log.info("Found {} existing weight allocations in Redis. Syncing to Nginx...", weights.size());
                syncWeightsToNginx(weights);
            } else {
                log.info("No existing weight allocations found in Redis");
            }
        } catch (Exception e) {
            log.error("Error during initial sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Periodically sync Redis data to Nginx configuration
     * This runs on all instances and updates local Nginx based on Redis state
     */
    @Scheduled(fixedDelayString = "${loadbalancer.redis.intervals.config-sync:10000}")
    public void syncRedisToNginx() {
        syncCount++;
        
        try {
            log.debug("[{}] Starting Redis -> Nginx sync (sync #{})", instanceId, syncCount);

            // Check Redis health
            if (!redisStateService.isRedisHealthy()) {
                log.warn("[{}] Redis is not healthy. Skipping sync.", instanceId);
                failedSyncs++;
                return;
            }

            // Get weight allocations from Redis
            List<WeightAllocation> weights = redisStateService.getWeightAllocations();
            
            if (weights.isEmpty()) {
                log.debug("[{}] No weight allocations found in Redis. Skipping sync.", instanceId);
                return;
            }

            // Check if weights were recently updated
            Optional<LocalDateTime> lastCalculation = redisStateService.getLastWeightCalculationTime();
            
            if (lastCalculation.isPresent()) {
                LocalDateTime calcTime = lastCalculation.get();
                
                // Only sync if this is newer than our last sync
                if (lastSyncTime != null && !calcTime.isAfter(lastSyncTime)) {
                    log.debug("[{}] No new weight calculations since last sync. Skipping.", instanceId);
                    return;
                }
                
                long secondsSinceCalc = ChronoUnit.SECONDS.between(calcTime, LocalDateTime.now());
                log.info("[{}] Syncing weights calculated {} seconds ago", instanceId, secondsSinceCalc);
            }

            // Sync weights to Nginx
            syncWeightsToNginx(weights);
            
            lastSyncTime = LocalDateTime.now();
            successfulSyncs++;
            
            log.info("[{}] Sync completed successfully (Total: {}, Success: {}, Failed: {})",
                instanceId, syncCount, successfulSyncs, failedSyncs);

        } catch (Exception e) {
            failedSyncs++;
            log.error("[{}] Error during Redis sync: {}", instanceId, e.getMessage(), e);
        }
    }

    /**
     * Apply weight allocations to Nginx configuration
     */
    private void syncWeightsToNginx(List<WeightAllocation> weights) {
        try {
            log.debug("[{}] Applying {} weight allocations to Nginx", instanceId, weights.size());
            
            // Update Nginx configuration
            nginxConfigService.updateUpstreamConfiguration(weights);
            
            // Optionally store the current config in Redis for monitoring
            // (This is mainly for visibility, not for syncing back)
            storeCurrentNginxConfig();
            
            log.info("[{}] Successfully applied weight allocations to Nginx", instanceId);
            
        } catch (Exception e) {
            log.error("[{}] Error applying weights to Nginx: {}", instanceId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Store current Nginx configuration in Redis for monitoring purposes
     */
    private void storeCurrentNginxConfig() {
        try {
            String configPath = nginxConfig.getNginx().getConfigPath();
            java.nio.file.Path path = java.nio.file.Paths.get(configPath);
            
            if (java.nio.file.Files.exists(path)) {
                String configContent = java.nio.file.Files.readString(path);
                redisStateService.storeNginxConfig(configContent);
                log.debug("[{}] Stored current Nginx config in Redis", instanceId);
            }
        } catch (Exception e) {
            log.warn("[{}] Could not store Nginx config in Redis: {}", instanceId, e.getMessage());
        }
    }

    /**
     * Periodic cleanup of old metrics in Redis
     */
    @Scheduled(fixedDelayString = "${loadbalancer.redis.intervals.metrics-cleanup:60000}")
    public void cleanupOldMetrics() {
        try {
            log.debug("[{}] Running Redis metrics cleanup", instanceId);
            redisStateService.cleanupOldMetrics();
        } catch (Exception e) {
            log.error("[{}] Error during metrics cleanup: {}", instanceId, e.getMessage());
        }
    }

    /**
     * Get sync statistics
     */
    public SyncStats getSyncStats() {
        return SyncStats.builder()
            .instanceId(instanceId)
            .totalSyncs(syncCount)
            .successfulSyncs(successfulSyncs)
            .failedSyncs(failedSyncs)
            .lastSyncTime(lastSyncTime)
            .redisHealthy(redisStateService.isRedisHealthy())
            .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class SyncStats {
        private String instanceId;
        private int totalSyncs;
        private int successfulSyncs;
        private int failedSyncs;
        private LocalDateTime lastSyncTime;
        private boolean redisHealthy;
    }
}