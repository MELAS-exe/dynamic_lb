package com.intouch.cp.lb_aip_pidirect.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
                        WeightAllocation allocation = objectMapper.convertValue(item, WeightAllocation.class);
                        weights.add(allocation);
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
     * Store Nginx configuration in Redis
     */
    public void storeNginxConfig(String config) {
        try {
            String key = redisConfig.getKeys().getNginxConfigKey();
            redisTemplate.opsForValue().set(key, config,
                    Duration.ofSeconds(redisConfig.getTtl().getNginxConfig()));

            // Update last configuration update time
            String timestampKey = redisConfig.getKeys().getLastUpdateKey();
            redisTemplate.opsForValue().set(timestampKey, LocalDateTime.now(),
                    Duration.ofSeconds(redisConfig.getTtl().getNginxConfig()));

            log.info("Stored Nginx configuration in Redis (instance: {})", instanceId);
        } catch (Exception e) {
            log.error("Failed to store Nginx config in Redis: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieve current Nginx configuration from Redis
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
     * Get timestamp of last Nginx configuration update
     */
    public Optional<LocalDateTime> getLastNginxUpdateTime() {
        try {
            String key = redisConfig.getKeys().getLastUpdateKey();
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof LocalDateTime) {
                return Optional.of((LocalDateTime) value);
            } else if (value instanceof LinkedHashMap) {
                LocalDateTime timestamp = objectMapper.convertValue(value, LocalDateTime.class);
                return Optional.of(timestamp);
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve last update time from Redis: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Register this instance with a heartbeat
     */
    public void registerInstance() {
        try {
            String key = redisConfig.getKeys().getInstancePrefix() + instanceId;
            Map<String, Object> instanceInfo = new HashMap<>();
            instanceInfo.put("instanceId", instanceId);
            instanceInfo.put("lastHeartbeat", LocalDateTime.now());
            instanceInfo.put("status", "active");

            redisTemplate.opsForValue().set(key, instanceInfo,
                    Duration.ofSeconds(redisConfig.getTtl().getInstanceHeartbeat()));

            log.debug("Registered instance {} with heartbeat", instanceId);
        } catch (Exception e) {
            log.error("Failed to register instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    /**
     * Get all active instances
     */
    @SuppressWarnings("unchecked")
    public List<String> getActiveInstances() {
        try {
            String pattern = redisConfig.getKeys().getInstancePrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> activeInstances = new ArrayList<>();
            for (String key : keys) {
                Object value = redisTemplate.opsForValue().get(key);
                if (value instanceof Map) {
                    Map<String, Object> instanceInfo = (Map<String, Object>) value;
                    activeInstances.add((String) instanceInfo.get("instanceId"));
                }
            }

            return activeInstances;
        } catch (Exception e) {
            log.error("Failed to get active instances: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Acquire a distributed lock for critical operations
     */
    public boolean acquireLock(String lockName, long timeoutSeconds) {
        try {
            String key = redisConfig.getKeys().getLockPrefix() + lockName;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    instanceId,
                    Duration.ofSeconds(timeoutSeconds)
            );

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("Instance {} acquired lock: {}", instanceId, lockName);
                return true;
            }

            log.debug("Instance {} failed to acquire lock: {}", instanceId, lockName);
            return false;
        } catch (Exception e) {
            log.error("Failed to acquire lock {}: {}", lockName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release a distributed lock
     */
    public void releaseLock(String lockName) {
        try {
            String key = redisConfig.getKeys().getLockPrefix() + lockName;
            String lockOwner = (String) redisTemplate.opsForValue().get(key);

            // Only release if this instance owns the lock
            if (instanceId.equals(lockOwner)) {
                redisTemplate.delete(key);
                log.debug("Instance {} released lock: {}", instanceId, lockName);
            }
        } catch (Exception e) {
            log.error("Failed to release lock {}: {}", lockName, e.getMessage(), e);
        }
    }

    /**
     * Clean up old metrics
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
}