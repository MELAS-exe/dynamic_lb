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
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsCollectionService {

    private final ServerMetricsRepository metricsRepository;
    private final NginxConfig nginxConfig;
    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;

    public void receiveMetrics(String serverId, ServerMetrics metrics) {
        try {
            log.debug("Received metrics for server: {}", serverId);

            // Validate server exists in configuration
            if (nginxConfig.getServerById(serverId) == null) {
                log.warn("Received metrics for unknown server: {}", serverId);
                return;
            }

            // Set server ID and timestamp
            metrics.setServerId(serverId);
            metrics.setCreatedAt(LocalDateTime.now());

            // Validate metrics
            if (!isValidMetrics(metrics)) {
                log.warn("Invalid metrics received for server: {}", serverId);
                return;
            }

            // Save metrics
            metricsRepository.save(metrics);
            log.debug("Metrics saved for server: {} at {}", serverId, metrics.getCreatedAt());

            // Trigger weight recalculation if all servers have recent metrics
            triggerWeightRecalculationIfReady();

        } catch (Exception e) {
            log.error("Error processing metrics for server {}: {}", serverId, e.getMessage(), e);
        }
    }

    @Scheduled(fixedRateString = "#{${loadbalancer.simulation.interval-seconds:60} * 1000}")
    @Transactional
    public void processMetricsAndUpdateWeights() {
        try {
            log.debug("Processing metrics and updating weights");

            // Get latest metrics for all servers
            List<ServerMetrics> latestMetrics = metricsRepository.findLatestMetricsForAllServers();

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

            // Calculate new weights
            var weightAllocations = weightCalculationService.calculateWeights(freshMetrics);

            // Update NGINX configuration
            nginxConfigService.updateUpstreamConfiguration(weightAllocations);

            log.info("Successfully processed metrics for {} servers and updated weights",
                    freshMetrics.size());

        } catch (Exception e) {
            log.error("Error during metrics processing and weight update: {}", e.getMessage(), e);
        }
    }

    private void triggerWeightRecalculationIfReady() {
        // Check if we have recent metrics from all configured servers
        List<String> serverIds = nginxConfig.getServerIds();
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);

        long serversWithRecentMetrics = serverIds.stream()
                .mapToLong(serverId -> {
                    var metrics = metricsRepository.findFirstByServerIdOrderByCreatedAtDesc(serverId);
                    return metrics.map(m -> m.getCreatedAt().isAfter(cutoff) ? 1L : 0L).orElse(0L);
                })
                .sum();

        if (serversWithRecentMetrics >= serverIds.size() * 0.8) { // 80% of servers have recent metrics
            log.debug("Sufficient recent metrics available. Triggering weight recalculation.");
            processMetricsAndUpdateWeights();
        }
    }

    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    @Transactional
    public void cleanupOldMetrics() {
        try {
            log.info("Starting metrics cleanup");

            // Keep metrics for last 7 days
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            List<ServerMetrics> oldMetrics = metricsRepository.findByCreatedAtBefore(cutoff);

            if (!oldMetrics.isEmpty()) {
                metricsRepository.deleteByCreatedAtBefore(cutoff);
                log.info("Cleaned up {} old metric records", oldMetrics.size());
            } else {
                log.debug("No old metrics to clean up");
            }

        } catch (Exception e) {
            log.error("Error during metrics cleanup: {}", e.getMessage(), e);
        }
    }

    public List<ServerMetrics> getLatestMetricsForAllServers() {
        return metricsRepository.findLatestMetricsForAllServers();
    }

    public List<ServerMetrics> getMetricsForServer(String serverId) {
        return metricsRepository.findByServerIdOrderByCreatedAtDesc(serverId);
    }

    public List<ServerMetrics> getMetricsForServerInTimeRange(String serverId,
                                                              LocalDateTime start,
                                                              LocalDateTime end) {
        return metricsRepository.findByServerIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                serverId, start, end);
    }

    private boolean isValidMetrics(ServerMetrics metrics) {
        if (metrics == null) return false;

        // Check required fields
        if (metrics.getAvgResponseTimeMs() == null || metrics.getAvgResponseTimeMs() < 0) {
            log.warn("Invalid avgResponseTimeMs: {}", metrics.getAvgResponseTimeMs());
            return false;
        }

        if (metrics.getErrorRatePercentage() == null ||
                metrics.getErrorRatePercentage() < 0 ||
                metrics.getErrorRatePercentage() > 100) {
            log.warn("Invalid errorRatePercentage: {}", metrics.getErrorRatePercentage());
            return false;
        }

        if (metrics.getTimeoutRatePercentage() == null ||
                metrics.getTimeoutRatePercentage() < 0 ||
                metrics.getTimeoutRatePercentage() > 100) {
            log.warn("Invalid timeoutRatePercentage: {}", metrics.getTimeoutRatePercentage());
            return false;
        }

        if (metrics.getUptimePercentage() == null ||
                metrics.getUptimePercentage() < 0 ||
                metrics.getUptimePercentage() > 100) {
            log.warn("Invalid uptimePercentage: {}", metrics.getUptimePercentage());
            return false;
        }

        // Validate latency values
        if (metrics.getLatencyP50() != null && metrics.getLatencyP50() < 0) {
            log.warn("Invalid latencyP50: {}", metrics.getLatencyP50());
            return false;
        }

        if (metrics.getLatencyP95() != null && metrics.getLatencyP95() < 0) {
            log.warn("Invalid latencyP95: {}", metrics.getLatencyP95());
            return false;
        }

        if (metrics.getLatencyP99() != null && metrics.getLatencyP99() < 0) {
            log.warn("Invalid latencyP99: {}", metrics.getLatencyP99());
            return false;
        }

        return true;
    }

    public long getMetricsCount() {
        return metricsRepository.count();
    }

    public long getMetricsCountForServer(String serverId) {
        return metricsRepository.countByServerId(serverId);
    }
}