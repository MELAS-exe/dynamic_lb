package com.intouch.cp.lb_aip_pidirect.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

/**
 * Service to maintain heartbeat for this instance and track other active instances
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceHeartbeatService {

    private final RedisStateService redisStateService;

    @Value("${INSTANCE_ID:default-instance}")
    private String instanceId;

    @PostConstruct
    public void init() {
        log.info("Initializing heartbeat service for instance: {}", instanceId);
        registerInstance();
    }

    /**
     * Send heartbeat every 30 seconds
     */
    @Scheduled(fixedDelayString = "#{${loadbalancer.redis.intervals.heartbeat:30000}}")
    public void sendHeartbeat() {
        try {
            redisStateService.registerInstance();
            
            List<String> activeInstances = redisStateService.getActiveInstances();
            log.debug("Instance {} heartbeat sent. Active instances: {}", instanceId, activeInstances.size());
            
            if (log.isTraceEnabled()) {
                log.trace("Active instances: {}", activeInstances);
            }
        } catch (Exception e) {
            log.error("Failed to send heartbeat for instance {}: {}", instanceId, e.getMessage());
        }
    }

    /**
     * Register this instance on startup
     */
    private void registerInstance() {
        try {
            redisStateService.registerInstance();
            log.info("Instance {} registered successfully", instanceId);
        } catch (Exception e) {
            log.error("Failed to register instance {}: {}", instanceId, e.getMessage(), e);
        }
    }

    /**
     * Get list of all active instances
     */
    public List<String> getActiveInstances() {
        return redisStateService.getActiveInstances();
    }

    /**
     * Get this instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Cleanup on shutdown
     */
    @PreDestroy
    public void cleanup() {
        log.info("Instance {} shutting down, cleaning up...", instanceId);
        // Note: Instance will automatically expire from Redis after TTL
    }
}