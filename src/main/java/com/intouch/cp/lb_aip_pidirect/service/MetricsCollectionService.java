package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.repository.ServerMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enhanced MetricsCollectionService with Redis integration for shared state
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsCollectionService {

    private final ServerMetricsRepository metricsRepository;
    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;
    private final NginxConfig nginxConfig;
    private final RedisStateService redisStateService;

    /**
     * Process incoming metrics and store in both database and Redis
     */
    @Transactional
    public void processMetrics(String serverId, ServerMetrics metrics) {
        try {
            log.debug("Processing metrics for server: {}", serverId);

            if (metrics.getServerId() == null || metrics.getServerId().isEmpty()) {
                metrics.setServerId(serverId);
            }

            if (!serverId.equals(metrics.getServerId())) {
                log.warn("ServerId mismatch. URL: {}, Metrics: {}. Using URL serverId.",
                        serverId, metrics.getServerId());
                metrics.setServerId(serverId);
            }

            if (metrics.getCreatedAt() == null) {
                metrics.setCreatedAt(LocalDateTime.now());
            }

            if (nginxConfig.getServerById(serverId) == null) {
                log.warn("Metrics received for unknown server: {}", serverId);
                return;
            }

            // Calculate EWMA latency based on previous metrics
            calculateEwmaLatency(serverId, metrics);

            // Save metrics to database
            metricsRepository.save(metrics);

            // Store metrics in Redis for shared access
            redisStateService.storeMetrics(serverId, metrics);

            log.debug("Metrics saved for server: {} at {} (EWMA: {}ms, Instant: {}ms)",
                    serverId,
                    metrics.getCreatedAt(),
                    String.format("%.2f", metrics.getEwmaLatencyMs()),
                    String.format("%.2f", metrics.getAvgResponseTimeMs()));

            // Trigger weight recalculation if all servers have recent metrics
            triggerWeightRecalculationIfReady();

        } catch (Exception e) {
            log.error("Error processing metrics for server {}: {}", serverId, e.getMessage(), e);
        }
    }

    /**
     * Calculate EWMA latency: Lt = α * Mt + (1 - α) * Lt-1
     */
    private void calculateEwmaLatency(String serverId, ServerMetrics currentMetrics) {
        // Try to get previous metrics from Redis first
        Optional<ServerMetrics> previousMetrics = redisStateService.getMetrics(serverId);

        // Fallback to database if not in Redis
        if (previousMetrics.isEmpty()) {
            previousMetrics = metricsRepository.findFirstByServerIdOrderByCreatedAtDesc(serverId);
        }

        Double previousEwma = null;
        if (previousMetrics.isPresent()) {
            previousEwma = previousMetrics.get().getEwmaLatencyMs();
        }

        currentMetrics.calculateEwmaLatency(previousEwma);

        if (previousEwma != null) {
            log.debug("EWMA calculation for {}: Previous={}ms, Instant={}ms, New EWMA={}ms",
                    serverId,
                    String.format("%.2f", previousEwma),
                    String.format("%.2f", currentMetrics.getAvgResponseTimeMs()),
                    String.format("%.2f", currentMetrics.getEwmaLatencyMs()));
        } else {
            log.debug("First EWMA for {}: {}ms", serverId,
                    String.format("%.2f", currentMetrics.getEwmaLatencyMs()));
        }
    }

    /**
     * Scheduled task to process metrics and update weights
     * Uses Redis lock to ensure only one instance performs the update
     */
    @Scheduled(fixedRateString = "#{${loadbalancer.simulation.interval-seconds:60} * 1000}")
    @Transactional
    public void processMetricsAndUpdateWeights() {
        // Try to acquire lock for weight calculation
        if (!redisStateService.acquireLock("weight-calculation", 30)) {
            log.debug("Another instance is calculating weights, skipping");
            return;
        }

        try {
            log.debug("Processing metrics and updating weights");

            // Get latest metrics from Redis (shared across instances)
            List<ServerMetrics> latestMetrics = getLatestMetricsForAllServers();

            if (latestMetrics.isEmpty()) {
                log.warn("No metrics available for weight calculation");
                return;
            }

            // Filter out stale metrics (older than 5 minutes)
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
            List<ServerMetrics> freshMetrics = latestMetrics.stream()
                    .filter(metrics -> metrics.getCreatedAt().isAfter(cutoff))
                    .toList();

            if (freshMetrics.isEmpty()) {
                log.warn("All metrics are stale (older than 5 minutes)");
                return;
            }

            if (freshMetrics.size() < latestMetrics.size()) {
                log.warn("Some servers have stale metrics. Fresh: {}, Total: {}",
                        freshMetrics.size(), latestMetrics.size());
            }

            // Log EWMA values for monitoring
            freshMetrics.forEach(m ->
                    log.debug("Server {} - Instant: {}ms, EWMA: {}ms, Health: {:.3f}",
                            m.getServerId(),
                            String.format("%.2f", m.getAvgResponseTimeMs()),
                            String.format("%.2f", m.getEwmaLatencyMs()),
                            m.getHealthScore())
            );

            // Calculate new weights
            var weightAllocations = weightCalculationService.calculateWeights(freshMetrics);

            // Store weights in Redis
            redisStateService.storeWeights(weightAllocations);

            // Update NGINX configuration
            nginxConfigService.updateUpstreamConfiguration(weightAllocations);

            log.info("Successfully processed metrics for {} servers and updated weights",
                    freshMetrics.size());

        } catch (Exception e) {
            log.error("Error during metrics processing and weight update: {}", e.getMessage(), e);
        } finally {
            // Always release the lock
            redisStateService.releaseLock("weight-calculation");
        }
    }

    /**
     * Get latest metrics for all servers from Redis
     */
    public List<ServerMetrics> getLatestMetricsForAllServers() {
        try {
            // Get all metrics from Redis
            Map<String, ServerMetrics> metricsMap = redisStateService.getAllMetrics();

            if (!metricsMap.isEmpty()) {
                log.debug("Retrieved {} metrics from Redis", metricsMap.size());
                return new ArrayList<>(metricsMap.values());
            }

            // Fallback to database if Redis is empty
            log.debug("Redis metrics empty, falling back to database");
            return metricsRepository.findLatestMetricsForAllServers();
        } catch (Exception e) {
            log.error("Error retrieving latest metrics: {}", e.getMessage(), e);
            return metricsRepository.findLatestMetricsForAllServers();
        }
    }

    /**
     * Trigger weight recalculation if sufficient recent metrics are available
     */
    private void triggerWeightRecalculationIfReady() {
        List<String> serverIds = nginxConfig.getServerIds();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);

        long serversWithRecentMetrics = serverIds.stream()
                .mapToLong(serverId -> {
                    // Check Redis first
                    Optional<ServerMetrics> metrics = redisStateService.getMetrics(serverId);

                    // Fallback to database
                    if (metrics.isEmpty()) {
                        metrics = metricsRepository.findFirstByServerIdOrderByCreatedAtDesc(serverId);
                    }

                    return metrics.map(m -> m.getCreatedAt().isAfter(cutoff) ? 1L : 0L).orElse(0L);
                })
                .sum();

        if (serversWithRecentMetrics >= serverIds.size() * 0.8) {
            log.debug("Sufficient recent metrics available. Triggering weight recalculation.");
            processMetricsAndUpdateWeights();
        }
    }

    /**
     * Cleanup old metrics from both database and Redis
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldMetrics() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int deleted = metricsRepository.deleteByCreatedAtBefore(cutoff);

            // Cleanup Redis metrics
            redisStateService.cleanupOldMetrics();

            log.info("Cleanup completed: {} database records older than 7 days deleted", deleted);
        } catch (Exception e) {
            log.error("Error during metrics cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Get server metrics from Redis or database
     */
    public Optional<ServerMetrics> getServerMetrics(String serverId) {
        // Try Redis first
        Optional<ServerMetrics> metrics = redisStateService.getMetrics(serverId);

        // Fallback to database
        if (metrics.isEmpty()) {
            metrics = metricsRepository.findFirstByServerIdOrderByCreatedAtDesc(serverId);
        }

        return metrics;
    }
}