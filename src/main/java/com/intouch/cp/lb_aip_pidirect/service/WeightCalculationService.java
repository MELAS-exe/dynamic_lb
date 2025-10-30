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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Weight calculation with SEPARATE calculations for incoming and outgoing servers
 * Each group is normalized to sum to 100 independently
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeightCalculationService {

    private final NginxConfig nginxConfig;
    private final ServerConfigurationService configService;

    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 100;
    private static final int DEFAULT_WEIGHT = 10;
    private static final int TARGET_TOTAL = 100;

    /**
     * BACKWARD COMPATIBILITY: Calculate weights for all servers (combined)
     * This method maintains compatibility with existing code
     * Uses outgoing servers by default for backward compatibility
     */
    public List<WeightAllocation> calculateWeights(List<ServerMetrics> allMetrics) {
        log.debug("calculateWeights() called - using outgoing servers for backward compatibility");
        return calculateOutgoingWeights(allMetrics);
    }

    /**
     * Calculate weights for INCOMING servers only (normalized to 100)
     */
    public List<WeightAllocation> calculateIncomingWeights(List<ServerMetrics> allMetrics) {
        log.debug("Calculating weights for INCOMING servers");

        List<String> incomingServerIds = nginxConfig.getIncomingServerIds();
        List<ServerMetrics> incomingMetrics = allMetrics.stream()
                .filter(m -> incomingServerIds.contains(m.getServerId()))
                .collect(Collectors.toList());

        List<WeightAllocation> weights = calculateWeightsForGroup(
                incomingMetrics,
                nginxConfig.getIncomingServers(),
                "INCOMING"
        );

        log.info("INCOMING weights calculated: {} servers, total weight: {}",
                weights.size(),
                weights.stream().mapToInt(WeightAllocation::getWeight).sum());

        return weights;
    }

    /**
     * Calculate weights for OUTGOING servers only (normalized to 100)
     */
    public List<WeightAllocation> calculateOutgoingWeights(List<ServerMetrics> allMetrics) {
        log.debug("Calculating weights for OUTGOING servers");

        List<String> outgoingServerIds = nginxConfig.getOutgoingServerIds();
        List<ServerMetrics> outgoingMetrics = allMetrics.stream()
                .filter(m -> outgoingServerIds.contains(m.getServerId()))
                .collect(Collectors.toList());

        List<WeightAllocation> weights = calculateWeightsForGroup(
                outgoingMetrics,
                nginxConfig.getOutgoingServers(),
                "OUTGOING"
        );

        log.info("OUTGOING weights calculated: {} servers, total weight: {}",
                weights.size(),
                weights.stream().mapToInt(WeightAllocation::getWeight).sum());

        return weights;
    }

    /**
     * Calculate weights for a specific group of servers
     * Each group is normalized to sum to 100 independently
     */
    private List<WeightAllocation> calculateWeightsForGroup(
            List<ServerMetrics> metrics,
            List<ServerInfo> servers,
            String groupName) {

        List<WeightAllocation> allocations = new ArrayList<>();

        if (metrics.isEmpty()) {
            log.warn("{} group: No metrics available, using default weights", groupName);
            return assignDefaultWeightsForGroup(servers, groupName);
        }

        // Filter enabled servers
        List<ServerMetrics> enabledMetrics = metrics.stream()
                .filter(m -> {
                    ServerInfo server = findServerInList(servers, m.getServerId());
                    return server != null && server.isEnabled();
                })
                .collect(Collectors.toList());

        if (enabledMetrics.isEmpty()) {
            log.warn("{} group: All servers disabled, using default weights", groupName);
            return assignDefaultWeightsForGroup(servers, groupName);
        }

        // Calculate scores
        List<WeightScore> scores = new ArrayList<>();
        for (ServerMetrics m : enabledMetrics) {
            WeightScore score = calculateServerScore(m);
            scores.add(score);
        }

        // Normalize and assign weights
        normalizeAndAssignWeights(scores, allocations, servers);

        // Add disabled servers with 0 weight
        for (ServerMetrics m : metrics) {
            if (enabledMetrics.stream().noneMatch(em -> em.getServerId().equals(m.getServerId()))) {
                ServerInfo serverInfo = findServerInList(servers, m.getServerId());
                if (serverInfo != null) {
                    allocations.add(new WeightAllocation(
                            m.getServerId(),
                            serverInfo.getAddress(),
                            0,
                            0.0,
                            "Server manually disabled"
                    ));
                }
            }
        }

        ensureMinimumTraffic(allocations);
        applyFixedWeights(allocations);
        normalizeWeightsToTotal(allocations, TARGET_TOTAL);

        log.debug("{} group: Weight calculation completed. Active: {}, Total weight: {}",
                groupName,
                allocations.stream().filter(WeightAllocation::isActive).count(),
                allocations.stream().mapToInt(WeightAllocation::getWeight).sum());

        return allocations;
    }

    /**
     * Find server in a specific list
     */
    private ServerInfo findServerInList(List<ServerInfo> servers, String serverId) {
        return servers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Calculate score for a single server
     */
    private WeightScore calculateServerScore(ServerMetrics metrics) {
        if (!isMetricsValid(metrics)) {
            return new WeightScore(metrics.getServerId(), 0.0, "Invalid metrics");
        }

        NginxConfig.WeightFactors factors = nginxConfig.getWeightFactors();
        Double effectiveLatency = metrics.getEffectiveLatency();

        double responseTimeScore = calculateResponseTimeScore(effectiveLatency);
        double errorRateScore = calculateErrorRateScore(metrics.getErrorRatePercentage());
        double timeoutRateScore = calculateTimeoutRateScore(metrics.getTimeoutRatePercentage());
        double uptimeScore = calculateUptimeScore(metrics.getUptimePercentage());
        double successRateScore = calculateSuccessRateScore(metrics.getSuccessRatePercentage());
        double degradationScore = 1.0 - (metrics.getDegradationScore() != null ?
                Math.min(metrics.getDegradationScore() / 1000.0, 1.0) : 0.0);

        double compositeScore =
                (responseTimeScore * factors.getResponseTime()) +
                        (errorRateScore * factors.getErrorRate()) +
                        (timeoutRateScore * factors.getTimeoutRate()) +
                        (uptimeScore * factors.getUptime()) +
                        (degradationScore * factors.getDegradation());

        String reason = buildScoreReason(responseTimeScore, errorRateScore, timeoutRateScore,
                uptimeScore, degradationScore, successRateScore, effectiveLatency);

        return new WeightScore(metrics.getServerId(), compositeScore, reason);
    }

    private double calculateResponseTimeScore(Double latencyMs) {
        if (latencyMs == null || latencyMs <= 0) return 0.5;
        if (latencyMs <= 100) return 1.0;
        if (latencyMs <= 200) return 0.9;
        if (latencyMs <= 300) return 0.7;
        if (latencyMs <= 500) return 0.5;
        if (latencyMs <= 1000) return 0.3;
        return 0.1;
    }

    private double calculateErrorRateScore(Double errorRate) {
        if (errorRate == null) return 0.5;
        if (errorRate <= 0.5) return 1.0;
        if (errorRate <= 1.0) return 0.9;
        if (errorRate <= 2.0) return 0.7;
        if (errorRate <= 5.0) return 0.5;
        if (errorRate <= 10.0) return 0.3;
        return 0.1;
    }

    private double calculateTimeoutRateScore(Double timeoutRate) {
        if (timeoutRate == null) return 0.5;
        if (timeoutRate <= 0.1) return 1.0;
        if (timeoutRate <= 0.5) return 0.9;
        if (timeoutRate <= 1.0) return 0.7;
        if (timeoutRate <= 2.0) return 0.5;
        if (timeoutRate <= 5.0) return 0.3;
        return 0.1;
    }

    private double calculateUptimeScore(Double uptime) {
        if (uptime == null) return 0.5;
        if (uptime >= 99.9) return 1.0;
        if (uptime >= 99.5) return 0.9;
        if (uptime >= 99.0) return 0.8;
        if (uptime >= 98.0) return 0.6;
        if (uptime >= 95.0) return 0.4;
        return 0.2;
    }

    private double calculateSuccessRateScore(Double successRate) {
        if (successRate == null) return 0.5;
        if (successRate >= 99.5) return 1.0;
        if (successRate >= 99.0) return 0.9;
        if (successRate >= 98.0) return 0.8;
        if (successRate >= 95.0) return 0.6;
        if (successRate >= 90.0) return 0.4;
        return 0.2;
    }

    private void normalizeAndAssignWeights(List<WeightScore> scores,
                                           List<WeightAllocation> allocations,
                                           List<ServerInfo> servers) {
        double totalScore = scores.stream().mapToDouble(WeightScore::getRawScore).sum();

        if (totalScore <= 0) {
            assignDefaultWeights(scores, allocations, servers);
            return;
        }

        for (WeightScore score : scores) {
            ServerInfo serverInfo = findServerInList(servers, score.getServerId());
            if (serverInfo == null) continue;

            double normalizedScore = score.getRawScore() / totalScore;
            int weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT,
                    (int) Math.round(normalizedScore * 100)));

            if (score.getRawScore() < 0.1) weight = 0;

            allocations.add(new WeightAllocation(
                    score.getServerId(),
                    serverInfo.getAddress(),
                    weight,
                    score.getRawScore(),
                    score.getReason()
            ));
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

    private List<WeightAllocation> assignDefaultWeightsForGroup(List<ServerInfo> servers, String groupName) {
        List<WeightAllocation> allocations = new ArrayList<>();

        for (ServerInfo server : servers) {
            allocations.add(new WeightAllocation(
                    server.getId(),
                    server.getAddress(),
                    DEFAULT_WEIGHT,
                    0.5,
                    "Default weight - no metrics available"
            ));
        }

        log.warn("{} group: No metrics available. Assigning default weights to {} servers",
                groupName, allocations.size());

        applyFixedWeights(allocations);
        normalizeWeightsToTotal(allocations, TARGET_TOTAL);

        return allocations;
    }

    private void assignDefaultWeights(List<WeightScore> scores,
                                      List<WeightAllocation> allocations,
                                      List<ServerInfo> servers) {
        for (WeightScore score : scores) {
            ServerInfo serverInfo = findServerInList(servers, score.getServerId());
            if (serverInfo == null) continue;

            allocations.add(new WeightAllocation(
                    score.getServerId(),
                    serverInfo.getAddress(),
                    DEFAULT_WEIGHT,
                    score.getRawScore(),
                    "Default weight - all servers unhealthy"
            ));
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

    private void applyFixedWeights(List<WeightAllocation> allocations) {
        for (WeightAllocation allocation : allocations) {
            String serverId = allocation.getServerId();
            Integer calculatedWeight = allocation.getWeight();
            Integer effectiveWeight = configService.getEffectiveWeight(serverId, calculatedWeight);

            if (!effectiveWeight.equals(calculatedWeight)) {
                allocation.setWeight(effectiveWeight);
                allocation.setReason("Fixed weight: " + effectiveWeight + " (Dynamic: " + calculatedWeight + ")");
                log.info("Server {} - Applied fixed weight: {} (calculated: {})",
                        serverId, effectiveWeight, calculatedWeight);
            }
        }
    }

    /**
     * CRITICAL: Normalize weights to sum to exactly TARGET_TOTAL (100)
     * This ensures each group independently sums to 100
     */
    private void normalizeWeightsToTotal(List<WeightAllocation> allocations, int targetTotal) {
        if (allocations.isEmpty()) return;

        int currentTotal = allocations.stream().mapToInt(WeightAllocation::getWeight).sum();

        if (currentTotal == 0) {
            for (WeightAllocation allocation : allocations) {
                int weight = Math.max(targetTotal / allocations.size(), 1);
                allocation.setWeight(weight);
                allocation.setReason("Equal distribution: " + weight);
            }
            return;
        }

        double scaleFactor = (double) targetTotal / currentTotal;
        int assignedWeight = 0;

        for (int i = 0; i < allocations.size(); i++) {
            WeightAllocation allocation = allocations.get(i);
            int originalWeight = allocation.getWeight();

            if (i == allocations.size() - 1) {
                int finalWeight = targetTotal - assignedWeight;
                allocation.setWeight(Math.max(1, finalWeight));
            } else {
                int scaledWeight = Math.max(1, (int) Math.round(originalWeight * scaleFactor));
                allocation.setWeight(scaledWeight);
                assignedWeight += scaledWeight;
            }
        }

        log.debug("Normalized weights to total: {} (was: {})", targetTotal, currentTotal);
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