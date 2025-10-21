package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeightCalculationService {

    private final NginxConfig nginxConfig;
    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 100;
    private static final int DEFAULT_WEIGHT = 10;

    public List<WeightAllocation> calculateWeights(List<ServerMetrics> allMetrics) {
        log.debug("Calculating weights for {} servers", allMetrics.size());

        List<WeightAllocation> allocations = new ArrayList<>();

        if (allMetrics.isEmpty()) {
            return assignDefaultWeights();
        }

        List<ServerMetrics> enabledMetrics = allMetrics.stream()
                .filter(metrics -> {
                    ServerInfo server = nginxConfig.getServerById(metrics.getServerId());
                    return server != null && server.isEnabled();
                })
                .toList();

        if (enabledMetrics.isEmpty()) {
            log.warn("All servers are disabled. Using default weights.");
            return assignDefaultWeights();
        }

        List<WeightScore> scores = new ArrayList<>();
        for (ServerMetrics metrics : enabledMetrics) {
            WeightScore score = calculateServerScore(metrics);
            scores.add(score);
            log.debug("Server {} - Instant: {}ms, EWMA: {}ms, Raw Score: {:.3f}",
                    metrics.getServerId(),
                    String.format("%.2f", metrics.getAvgResponseTimeMs()),
                    String.format("%.2f", metrics.getEwmaLatencyMs()),
                    score.getRawScore());
        }

        normalizeAndAssignWeights(scores, allocations);

        for (ServerMetrics metrics : allMetrics) {
            if (enabledMetrics.stream().noneMatch(m -> m.getServerId().equals(metrics.getServerId()))) {
                ServerInfo serverInfo = nginxConfig.getServerById(metrics.getServerId());
                if (serverInfo != null) {
                    WeightAllocation allocation = new WeightAllocation(
                            metrics.getServerId(),
                            serverInfo.getAddress(),
                            0,
                            0.0,
                            "Server manually disabled"
                    );
                    allocations.add(allocation);
                }
            }
        }

        ensureMinimumTraffic(allocations);

        log.info("Weight calculation completed. Active servers: {}",
                allocations.stream().mapToInt(w -> w.isActive() ? 1 : 0).sum());

        return allocations;
    }

    private WeightScore calculateServerScore(ServerMetrics metrics) {
        if (!isMetricsValid(metrics)) {
            return new WeightScore(metrics.getServerId(), 0.0, "Invalid metrics");
        }

        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();

        Double effectiveLatency = metrics.getEffectiveLatency();

        double responseTimeScore = calculateResponseTimeScore(effectiveLatency);
        double errorRateScore = calculateErrorRateScore(metrics.getErrorRatePercentage());
        double successRateScore = calculateSuccessRateScore(metrics.getSuccessRatePercentage());
        double timeoutScore = calculateTimeoutScore(metrics.getTimeoutRatePercentage());
        double uptimeScore = calculateUptimeScore(metrics.getUptimePercentage());
        double degradationScore = calculateDegradationScore(metrics.getDegradationScore());

        double rawScore = (responseTimeScore * factors.getResponseTime()) +
                (errorRateScore * factors.getErrorRate()) +
                (timeoutScore * factors.getTimeoutRate()) +
                (uptimeScore * factors.getUptime()) +
                (degradationScore * factors.getDegradation());

        String reason = buildScoreReason(responseTimeScore, errorRateScore, timeoutScore,
                uptimeScore, degradationScore, successRateScore, effectiveLatency);

        return new WeightScore(metrics.getServerId(), rawScore, reason);
    }

    private double calculateResponseTimeScore(Double responseTimeMs) {
        if (responseTimeMs == null || responseTimeMs <= 0) return 0.0;

        if (responseTimeMs <= 200) return 1.0;
        if (responseTimeMs <= 500) return 1.0 - ((responseTimeMs - 200) / 300) * 0.5;
        if (responseTimeMs <= 1000) return 0.5 - ((responseTimeMs - 500) / 500) * 0.4;
        return Math.max(0.0, 0.1 - ((responseTimeMs - 1000) / 2000) * 0.1);
    }

    private double calculateErrorRateScore(Double errorRate) {
        if (errorRate == null) return 0.0;
        if (errorRate <= 0) return 1.0;
        if (errorRate >= 10) return 0.0;
        return 1.0 - (errorRate / 10.0);
    }

    private double calculateSuccessRateScore(Double successRate) {
        if (successRate == null) return 0.0;
        if (successRate >= 100) return 1.0;
        if (successRate <= 90) return 0.0;
        return (successRate - 90.0) / 10.0;
    }

    private double calculateTimeoutScore(Double timeoutRate) {
        if (timeoutRate == null) return 0.0;
        if (timeoutRate <= 0) return 1.0;
        if (timeoutRate >= 5) return 0.0;
        return 1.0 - (timeoutRate / 5.0);
    }

    private double calculateUptimeScore(Double uptime) {
        if (uptime == null) return 0.0;
        if (uptime >= 99.5) return 1.0;
        if (uptime <= 90.0) return 0.0;
        return (uptime - 90.0) / 9.5;
    }

    private double calculateDegradationScore(Double degradation) {
        if (degradation == null) return 1.0;
        if (degradation <= 0) return 1.0;
        if (degradation >= 500) return 0.0;
        return 1.0 - (degradation / 500.0);
    }

    private void normalizeAndAssignWeights(List<WeightScore> scores, List<WeightAllocation> allocations) {
        double totalScore = scores.stream().mapToDouble(WeightScore::getRawScore).sum();

        if (totalScore <= 0) {
            assignDefaultWeights(scores, allocations);
            return;
        }

        for (WeightScore score : scores) {
            ServerInfo serverInfo = nginxConfig.getServerById(score.getServerId());
            if (serverInfo == null) continue;

            double normalizedScore = score.getRawScore() / totalScore;
            int weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT,
                    (int) Math.round(normalizedScore * 100)));

            if (score.getRawScore() < 0.1) {
                weight = 0;
            }

            WeightAllocation allocation = new WeightAllocation(
                    score.getServerId(),
                    serverInfo.getAddress(),
                    weight,
                    score.getRawScore(),
                    score.getReason()
            );

            allocations.add(allocation);
        }
    }

    private void ensureMinimumTraffic(List<WeightAllocation> allocations) {
        boolean hasActiveServer = allocations.stream().anyMatch(WeightAllocation::isActive);

        if (!hasActiveServer && !allocations.isEmpty()) {
            WeightAllocation best = allocations.stream()
                    .max((a, b) -> Double.compare(a.getHealthScore(), b.getHealthScore()))
                    .orElse(null);

            if (best != null) {
                best.setWeight(MIN_WEIGHT);
                best.setReason("Emergency fallback - no healthy servers");
                log.warn("No healthy servers found. Assigning minimal traffic to: {}", best.getServerId());
            }
        }
    }

    private List<WeightAllocation> assignDefaultWeights() {
        List<WeightAllocation> allocations = new ArrayList<>();

        for (ServerInfo server : nginxConfig.getServers()) {
            WeightAllocation allocation = new WeightAllocation(
                    server.getId(),
                    server.getAddress(),
                    DEFAULT_WEIGHT,
                    0.5,
                    "Default weight - no metrics available"
            );
            allocations.add(allocation);
        }

        log.warn("No metrics available. Assigning default weights to {} servers", allocations.size());
        return allocations;
    }

    private void assignDefaultWeights(List<WeightScore> scores, List<WeightAllocation> allocations) {
        for (WeightScore score : scores) {
            ServerInfo serverInfo = nginxConfig.getServerById(score.getServerId());
            if (serverInfo == null) continue;

            WeightAllocation allocation = new WeightAllocation(
                    score.getServerId(),
                    serverInfo.getAddress(),
                    DEFAULT_WEIGHT,
                    score.getRawScore(),
                    "Default weight - all servers unhealthy"
            );
            allocations.add(allocation);
        }
    }

    private boolean isMetricsValid(ServerMetrics metrics) {
        return metrics != null &&
                metrics.getAvgResponseTimeMs() != null &&
                metrics.getErrorRatePercentage() != null &&
                metrics.getSuccessRatePercentage() != null &&
                metrics.getTimeoutRatePercentage() != null &&
                metrics.getUptimePercentage() != null;
    }

    private String buildScoreReason(double responseTime, double errorRate, double timeout,
                                    double uptime, double degradation, double successRate,
                                    Double effectiveLatency) {
        return String.format("EWMA:%.1fms SR:%.2f RT:%.2f ER:%.2f TO:%.2f UP:%.2f DEG:%.2f",
                effectiveLatency != null ? effectiveLatency : 0.0,
                successRate, responseTime, errorRate, timeout, uptime, degradation);
    }

    private static class WeightScore {
        private final String serverId;
        private final double rawScore;
        private final String reason;

        public WeightScore(String serverId, double rawScore, String reason) {
            this.serverId = serverId;
            this.rawScore = rawScore;
            this.reason = reason;
        }

        public String getServerId() { return serverId; }
        public double getRawScore() { return rawScore; }
        public String getReason() { return reason; }
    }
}