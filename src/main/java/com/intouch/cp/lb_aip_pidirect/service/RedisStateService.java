package com.intouch.cp.lb_aip_pidirect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intouch.cp.lb_aip_pidirect.config.RedisConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing shared state across multiple load balancer instances using Redis.
 * This service ensures all instances have synchronized configuration and metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisStateService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConfig redisConfig;
    private final ObjectMapper objectMapper;

    @Value("${INSTANCE_ID:default-instance}")
    private String instanceId;

    /**
     * Store server metrics in Redis
     */
    public void storeMetrics(String serverId, ServerMetrics metrics) {
        try {
            String key = redisConfig.getKeys().getMetricsPrefix() + serverId;
            redisTemplate.opsForValue().set(key, metrics,
                    Duration.ofSeconds(redisConfig.getTtl().getMetrics()));

            log.debug("Stored metrics for server {} in Redis (instance: {})", serverId, instanceId);
        } catch (Exception e) {
            log.error("Failed to store metrics for server {} in Redis: {}", serverId, e.getMessage(), e);
        }
    }

    /**
     * Retrieve server metrics from Redis
     */
    public Optional<ServerMetrics> getMetrics(String serverId) {
        try {
            String key = redisConfig.getKeys().getMetricsPrefix() + serverId;
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof ServerMetrics) {
                return Optional.of((ServerMetrics) value);
            } else if (value instanceof LinkedHashMap) {
                // Handle deserialization from LinkedHashMap
                ServerMetrics metrics = objectMapper.convertValue(value, ServerMetrics.class);
                return Optional.of(metrics);
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve metrics for server {} from Redis: {}", serverId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * ALIAS METHOD: Get server metrics (same as getMetrics)
     */
    public Optional<ServerMetrics> getServerMetrics(String serverId) {
        return getMetrics(serverId);
    }

    /**
     * Get all server metrics from Redis
     */
    public Map<String, ServerMetrics> getAllMetrics() {
        try {
            String pattern = redisConfig.getKeys().getMetricsPrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, ServerMetrics> metricsMap = new HashMap<>();
            for (String key : keys) {
                String serverId = key.substring(redisConfig.getKeys().getMetricsPrefix().length());
                getMetrics(serverId).ifPresent(metrics -> metricsMap.put(serverId, metrics));
            }

            log.debug("Retrieved {} server metrics from Redis", metricsMap.size());
            return metricsMap;
        } catch (Exception e) {
            log.error("Failed to retrieve all metrics from Redis: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * ALIAS METHOD: Get all server metrics (same as getAllMetrics)
     */
    public Map<String, ServerMetrics> getAllServerMetrics() {
        return getAllMetrics();
    }

    /**
     * Store weight allocations in Redis
     */
    public void storeWeights(List<WeightAllocation> weights) {
        try {
            String key = redisConfig.getKeys().getWeightsPrefix() + "current";
            redisTemplate.opsForValue().set(key, weights,
                    Duration.ofSeconds(redisConfig.getTtl().getWeights()));

            // Store last update timestamp
            String timestampKey = redisConfig.getKeys().getWeightsPrefix() + "last-update";
            redisTemplate.opsForValue().set(timestampKey, LocalDateTime.now(),
                    Duration.ofSeconds(redisConfig.getTtl().getWeights()));

            log.info("Stored {} weight allocations in Redis (instance: {})", weights.size(), instanceId);
        } catch (Exception e) {
            log.error("Failed to store weights in Redis: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieve current weight allocations from Redis
     */
    @SuppressWarnings("unchecked")
    public Optional<List<WeightAllocation>> getWeights() {
        try {
            String key = redisConfig.getKeys().getWeightsPrefix() + "current";
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                return Optional.empty();
            }

            if (value instanceof List) {
                List<?> rawList = (List<?>) value;
                List<WeightAllocation> weights = new ArrayList<>();

                for (Object item : rawList) {
                    if (item instanceof WeightAllocation) {
                        weights.add((WeightAllocation) item);
                    } else if (item instanceof LinkedHashMap) {
                        WeightAllocation weight = objectMapper.convertValue(item, WeightAllocation.class);
                        weights.add(weight);
                    }
                }

                return Optional.of(weights);
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve weights from Redis: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get weight allocations as a list (convenience method)
     */
    public List<WeightAllocation> getWeightAllocations() {
        return getWeights().orElse(Collections.emptyList());
    }

    /**
     * Get last weight calculation time
     */
    public Optional<LocalDateTime> getLastWeightCalculationTime() {
        try {
            String key = redisConfig.getKeys().getWeightsPrefix() + "last-update";
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof LocalDateTime) {
                return Optional.of((LocalDateTime) value);
            } else if (value != null) {
                return Optional.of(objectMapper.convertValue(value, LocalDateTime.class));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve last weight calculation time: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Store Nginx configuration in Redis
     */
    public void storeNginxConfig(String config) {
        try {
            String key = redisConfig.getKeys().getNginxConfigKey();
            redisTemplate.opsForValue().set(key, config,
                    Duration.ofSeconds(redisConfig.getTtl().getNginxConfig()));

            // Store last update timestamp
            String timestampKey = redisConfig.getKeys().getLastUpdateKey();
            redisTemplate.opsForValue().set(timestampKey, LocalDateTime.now(),
                    Duration.ofSeconds(redisConfig.getTtl().getNginxConfig()));

            log.info("Stored Nginx config in Redis (instance: {})", instanceId);
        } catch (Exception e) {
            log.error("Failed to store Nginx config in Redis: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieve Nginx configuration from Redis
     */
    public Optional<String> getNginxConfig() {
        try {
            String key = redisConfig.getKeys().getNginxConfigKey();
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof String) {
                return Optional.of((String) value);
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve Nginx config from Redis: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get last Nginx update time
     */
    public Optional<LocalDateTime> getLastNginxUpdateTime() {
        try {
            String key = redisConfig.getKeys().getLastUpdateKey();
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof LocalDateTime) {
                return Optional.of((LocalDateTime) value);
            } else if (value != null) {
                return Optional.of(objectMapper.convertValue(value, LocalDateTime.class));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve last Nginx update time: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Acquire a distributed lock
     */
    public boolean acquireLock(String lockName, int ttlSeconds) {
        try {
            String key = redisConfig.getKeys().getLockPrefix() + lockName;
            Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(key, instanceId, Duration.ofSeconds(ttlSeconds));

            if (Boolean.TRUE.equals(success)) {
                log.debug("Lock '{}' acquired by instance: {}", lockName, instanceId);
                return true;
            }

            log.debug("Lock '{}' already held by another instance", lockName);
            return false;
        } catch (Exception e) {
            log.error("Failed to acquire lock '{}': {}", lockName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release a distributed lock
     */
    public void releaseLock(String lockName) {
        try {
            String key = redisConfig.getKeys().getLockPrefix() + lockName;

            // Only release if this instance owns the lock
            Object lockOwner = redisTemplate.opsForValue().get(key);
            if (instanceId.equals(lockOwner)) {
                redisTemplate.delete(key);
                log.debug("Lock '{}' released by instance: {}", lockName, instanceId);
            }
        } catch (Exception e) {
            log.error("Failed to release lock '{}': {}", lockName, e.getMessage(), e);
        }
    }

    /**
     * Store instance heartbeat
     */
    public void storeHeartbeat(String instanceId) {
        try {
            String key = redisConfig.getKeys().getInstancePrefix() + instanceId;
            redisTemplate.opsForValue().set(key, LocalDateTime.now(),
                    Duration.ofSeconds(redisConfig.getTtl().getInstanceHeartbeat()));

            log.debug("Heartbeat stored for instance: {}", instanceId);
        } catch (Exception e) {
            log.error("Failed to store heartbeat for instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    /**
     * Get active instances
     */
    public List<String> getActiveInstances() {
        try {
            String pattern = redisConfig.getKeys().getInstancePrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> activeInstances = new ArrayList<>();
            for (String key : keys) {
                String instance = key.substring(redisConfig.getKeys().getInstancePrefix().length());
                activeInstances.add(instance);
            }

            log.debug("Found {} active instances", activeInstances.size());
            return activeInstances;
        } catch (Exception e) {
            log.error("Failed to get active instances: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Cleanup old metrics
     */
    public void cleanupOldMetrics() {
        try {
            String pattern = redisConfig.getKeys().getMetricsPrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return;
            }

            int cleaned = 0;
            for (String key : keys) {
                Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                if (ttl != null && ttl < 0) {
                    redisTemplate.delete(key);
                    cleaned++;
                }
            }

            if (cleaned > 0) {
                log.info("Cleaned up {} expired metric entries", cleaned);
            }
        } catch (Exception e) {
            log.error("Failed to cleanup old metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Get Redis health status
     */
    public boolean isHealthy() {
        try {
            redisTemplate.opsForValue().get("health-check");
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ALIAS METHOD: Check if Redis is healthy (same as isHealthy)
     */
    public boolean isRedisHealthy() {
        return isHealthy();
    }

    /**
     * Get comprehensive Redis statistics
     */
    public Map<String, Object> getRedisStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            // Basic health
            stats.put("healthy", isHealthy());

            // Count keys by type
            String metricsPattern = redisConfig.getKeys().getMetricsPrefix() + "*";
            Set<String> metricsKeys = redisTemplate.keys(metricsPattern);
            stats.put("metricsCount", metricsKeys != null ? metricsKeys.size() : 0);

            String weightsPattern = redisConfig.getKeys().getWeightsPrefix() + "*";
            Set<String> weightsKeys = redisTemplate.keys(weightsPattern);
            stats.put("weightsKeysCount", weightsKeys != null ? weightsKeys.size() : 0);

            String instancePattern = redisConfig.getKeys().getInstancePrefix() + "*";
            Set<String> instanceKeys = redisTemplate.keys(instancePattern);
            stats.put("activeInstancesCount", instanceKeys != null ? instanceKeys.size() : 0);

            String lockPattern = redisConfig.getKeys().getLockPrefix() + "*";
            Set<String> lockKeys = redisTemplate.keys(lockPattern);
            stats.put("activeLocksCount", lockKeys != null ? lockKeys.size() : 0);

            // Data availability
            stats.put("weightsAvailable", getWeights().isPresent());
            stats.put("nginxConfigAvailable", getNginxConfig().isPresent());

            // Timestamps
            getLastWeightCalculationTime().ifPresent(time ->
                    stats.put("lastWeightCalculation", time));
            getLastNginxUpdateTime().ifPresent(time ->
                    stats.put("lastNginxUpdate", time));

            // Active instances
            stats.put("activeInstances", getActiveInstances());

        } catch (Exception e) {
            log.error("Error gathering Redis stats: {}", e.getMessage(), e);
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * Store arbitrary configuration data
     */
    public void storeConfig(String configKey, Object configData) {
        try {
            String key = redisConfig.getKeys().getConfigPrefix() + configKey;
            redisTemplate.opsForValue().set(key, configData,
                    Duration.ofSeconds(redisConfig.getTtl().getConfig()));

            log.debug("Stored config {} in Redis", configKey);
        } catch (Exception e) {
            log.error("Failed to store config {}: {}", configKey, e.getMessage(), e);
        }
    }

    /**
     * Retrieve arbitrary configuration data
     */
    public Optional<Object> getConfig(String configKey) {
        try {
            String key = redisConfig.getKeys().getConfigPrefix() + configKey;
            Object value = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.error("Failed to retrieve config {}: {}", configKey, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Clear all load balancer data from Redis (for testing/reset)
     */
    public void clearAllData() {
        try {
            log.warn("Clearing all load balancer data from Redis");

            // Clear metrics
            String metricsPattern = redisConfig.getKeys().getMetricsPrefix() + "*";
            Set<String> metricsKeys = redisTemplate.keys(metricsPattern);
            if (metricsKeys != null && !metricsKeys.isEmpty()) {
                redisTemplate.delete(metricsKeys);
                log.info("Cleared {} metrics keys", metricsKeys.size());
            }

            // Clear weights
            String weightsPattern = redisConfig.getKeys().getWeightsPrefix() + "*";
            Set<String> weightsKeys = redisTemplate.keys(weightsPattern);
            if (weightsKeys != null && !weightsKeys.isEmpty()) {
                redisTemplate.delete(weightsKeys);
                log.info("Cleared {} weights keys", weightsKeys.size());
            }

            // Clear config
            String configPattern = redisConfig.getKeys().getConfigPrefix() + "*";
            Set<String> configKeys = redisTemplate.keys(configPattern);
            if (configKeys != null && !configKeys.isEmpty()) {
                redisTemplate.delete(configKeys);
                log.info("Cleared {} config keys", configKeys.size());
            }

            // Clear nginx config
            redisTemplate.delete(redisConfig.getKeys().getNginxConfigKey());
            redisTemplate.delete(redisConfig.getKeys().getLastUpdateKey());

            // Clear locks
            String lockPattern = redisConfig.getKeys().getLockPrefix() + "*";
            Set<String> lockKeys = redisTemplate.keys(lockPattern);
            if (lockKeys != null && !lockKeys.isEmpty()) {
                redisTemplate.delete(lockKeys);
                log.info("Cleared {} lock keys", lockKeys.size());
            }

            // Clear instance heartbeats
            String instancePattern = redisConfig.getKeys().getInstancePrefix() + "*";
            Set<String> instanceKeys = redisTemplate.keys(instancePattern);
            if (instanceKeys != null && !instanceKeys.isEmpty()) {
                redisTemplate.delete(instanceKeys);
                log.info("Cleared {} instance keys", instanceKeys.size());
            }

            log.info("Successfully cleared all load balancer data from Redis");
        } catch (Exception e) {
            log.error("Failed to clear Redis data: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear Redis data", e);
        }
    }

    /**
     * Register a new load balancer instance in Redis.
     * This helps keep track of all running instances in the cluster.
     */
    public void registerInstance(String instanceId) {
        try {
            String key = redisConfig.getKeys().getInstancePrefix() + instanceId;
            LocalDateTime now = LocalDateTime.now();

            redisTemplate.opsForValue().set(
                    key,
                    now,
                    Duration.ofSeconds(redisConfig.getTtl().getInstanceHeartbeat())
            );

            log.info("Registered new load balancer instance '{}' at {}", instanceId, now);
        } catch (Exception e) {
            log.error("Failed to register instance {}: {}", instanceId, e.getMessage(), e);
        }
    }
}