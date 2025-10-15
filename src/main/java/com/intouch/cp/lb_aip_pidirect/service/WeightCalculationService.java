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
            // No metrics available, assign default weights
            return assignDefaultWeights();
        }

        // Filter out metrics for disabled servers
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

        // Calculate raw scores for each enabled server
        List<WeightScore> scores = new ArrayList<>();
        for (ServerMetrics metrics : enabledMetrics) {
            WeightScore score = calculateServerScore(metrics);
            scores.add(score);
            log.debug("Server {} raw score: {}", metrics.getServerId(), score.getRawScore());
        }

        // Normalize scores to weights (1-100)
        normalizeAndAssignWeights(scores, allocations);

        // Add disabled servers with weight 0
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

        // Ensure at least one server has traffic if any are healthy
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

        // Calculate individual component scores (0-1)
        double responseTimeScore = calculateResponseTimeScore(metrics.getAvgResponseTimeMs());
        double errorRateScore = calculateErrorRateScore(metrics.getErrorRatePercentage());
        double timeoutScore = calculateTimeoutScore(metrics.getTimeoutRatePercentage());
        double uptimeScore = calculateUptimeScore(metrics.getUptimePercentage());
        double degradationScore = calculateDegradationScore(metrics.getDegradationScore());

        // Weighted combination
        double rawScore = (responseTimeScore * factors.getResponseTime()) +
                (errorRateScore * factors.getErrorRate()) +
                (timeoutScore * factors.getTimeoutRate()) +
                (uptimeScore * factors.getUptime()) +
                (degradationScore * factors.getDegradation());

        String reason = buildScoreReason(responseTimeScore, errorRateScore, timeoutScore,
                uptimeScore, degradationScore);

        return new WeightScore(metrics.getServerId(), rawScore, reason);
    }

    private double calculateResponseTimeScore(Double responseTimeMs) {
        if (responseTimeMs == null || responseTimeMs <= 0) return 0.0;

        // Good response time: 0-200ms = score 1.0
        // Acceptable: 200-500ms = score 0.5-1.0
        // Poor: 500-1000ms = score 0.1-0.5
        // Very poor: >1000ms = score 0.0-0.1

        if (responseTimeMs <= 200) return 1.0;
        if (responseTimeMs <= 500) return 1.0 - ((responseTimeMs - 200) / 300) * 0.5;
        if (responseTimeMs <= 1000) return 0.5 - ((responseTimeMs - 500) / 500) * 0.4;
        return Math.max(0.0, 0.1 - ((responseTimeMs - 1000) / 2000) * 0.1);
    }

    private double calculateErrorRateScore(Double errorRate) {
        if (errorRate == null) return 0.0;
        if (errorRate <= 0) return 1.0;
        if (errorRate >= 10) return 0.0;

        // Linear decrease: 0% = 1.0, 10% = 0.0
        return 1.0 - (errorRate / 10.0);
    }

    private double calculateTimeoutScore(Double timeoutRate) {
        if (timeoutRate == null) return 0.0;
        if (timeoutRate <= 0) return 1.0;
        if (timeoutRate >= 5) return 0.0;

        // Linear decrease: 0% = 1.0, 5% = 0.0
        return 1.0 - (timeoutRate / 5.0);
    }

    private double calculateUptimeScore(Double uptime) {
        if (uptime == null) return 0.0;
        if (uptime >= 99.5) return 1.0;
        if (uptime <= 90.0) return 0.0;

        // Linear scale: 90% = 0.0, 99.5% = 1.0
        return (uptime - 90.0) / 9.5;
    }

    private double calculateDegradationScore(Double degradation) {
        if (degradation == null) return 1.0;
        if (degradation <= 0) return 1.0;
        if (degradation >= 500) return 0.0;

        // Linear decrease: 0 = 1.0, 500 = 0.0
        return 1.0 - (degradation / 500.0);
    }

    private void normalizeAndAssignWeights(List<WeightScore> scores, List<WeightAllocation> allocations) {
        double totalScore = scores.stream().mapToDouble(WeightScore::getRawScore).sum();

        if (totalScore <= 0) {
            // All servers are unhealthy, assign minimal weights
            assignDefaultWeights(scores, allocations);
            return;
        }

        for (WeightScore score : scores) {
            ServerInfo serverInfo = nginxConfig.getServerById(score.getServerId());
            if (serverInfo == null) continue;

            // Calculate weight as percentage of total, scaled to 1-100
            double normalizedScore = score.getRawScore() / totalScore;
            int weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT,
                    (int) Math.round(normalizedScore * 100)));

            // If score is very low, set weight to 0 (no traffic)
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
            // No active servers, assign minimal weight to the best scoring one
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
                metrics.getTimeoutRatePercentage() != null &&
                metrics.getUptimePercentage() != null;
    }

    private String buildScoreReason(double responseTime, double errorRate, double timeout,
                                    double uptime, double degradation) {
        StringBuilder reason = new StringBuilder();
        reason.append(String.format("Scores - RT:%.2f ER:%.2f TO:%.2f UP:%.2f DEG:%.2f",
                responseTime, errorRate, timeout, uptime, degradation));
        return reason.toString();
    }

    // Inner class to hold calculated scores
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