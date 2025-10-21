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

            // Calculate EWMA latency based on previous metrics
            calculateEwmaLatency(serverId, metrics);

            // Save metrics
            metricsRepository.save(metrics);
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
     * Calcule la latence EWMA pour les nouvelles métriques
     * Lt = α * Mt + (1 - α) * Lt-1
     */
    private void calculateEwmaLatency(String serverId, ServerMetrics currentMetrics) {
        // Récupérer les métriques précédentes pour ce serveur
        var previousMetrics = metricsRepository.findFirstByServerIdOrderByCreatedAtDesc(serverId);

        Double previousEwma = null;
        if (previousMetrics.isPresent()) {
            previousEwma = previousMetrics.get().getEwmaLatencyMs();
        }

        // Calculer la nouvelle EWMA
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

        if (serversWithRecentMetrics >= serverIds.size() * 0.8) {
            log.debug("Sufficient recent metrics available. Triggering weight recalculation.");
            processMetricsAndUpdateWeights();
        }
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldMetrics() {
        try {
            log.info("Starting metrics cleanup");

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

        if (metrics.getSuccessRatePercentage() == null ||
                metrics.getSuccessRatePercentage() < 0 ||
                metrics.getSuccessRatePercentage() > 100) {
            log.warn("Invalid successRatePercentage: {}", metrics.getSuccessRatePercentage());
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

        if (metrics.getRequestsPerMinute() != null && metrics.getRequestsPerMinute() < 0) {
            log.warn("Invalid requestsPerMinute: {}", metrics.getRequestsPerMinute());
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