package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerConfiguration;
import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeightCalculationService {

    private final NginxConfig nginxConfig;
    private final ServerConfigurationService configService;

    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 100;
    private static final int DEFAULT_WEIGHT = 10;

    /**
     * Calculate weights for all servers (both incoming and outgoing)
     */
    public List<WeightAllocation> calculateWeights(List<ServerMetrics> allMetrics) {
        log.debug("Calculating weights for {} servers", allMetrics.size());

        List<WeightAllocation> allocations = new ArrayList<>();

        if (allMetrics.isEmpty()) {
            return assignDefaultWeights();
        }

        List<ServerMetrics> enabledMetrics = allMetrics.stream()
                .filter(metrics -> {
                    Optional<ServerInfo> server = nginxConfig.getServerById(metrics.getServerId());
                    return server.isPresent() && server.get().isEnabled();
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
                Optional<ServerInfo> serverInfo = nginxConfig.getServerById(metrics.getServerId());
                if (serverInfo.isPresent()) {
                    WeightAllocation allocation = new WeightAllocation(
                            metrics.getServerId(),
                            serverInfo.get().getAddress(),
                            0,
                            0.0,
                            "Server manually disabled"
                    );
                    allocations.add(allocation);
                }
            }
        }

        ensureMinimumTraffic(allocations);

        // CRITICAL FIX: Apply fixed weights from configuration AFTER calculation
        applyFixedWeights(allocations);

        // NORMALIZATION: Ensure weights always sum to exactly 100
        normalizeWeightsToTotal(allocations, 100);

        log.info("Weight calculation completed. Active servers: {}",
                allocations.stream().filter(WeightAllocation::isActive).count());

        return allocations;
    }

    /**
     * Calculate weights specifically for incoming servers
     */
    public List<WeightAllocation> calculateIncomingWeights(List<ServerMetrics> incomingMetrics) {
        log.debug("Calculating weights for {} incoming servers", incomingMetrics.size());
        return calculateWeightsForServerGroup(incomingMetrics, nginxConfig.getIncomingServers(), "incoming");
    }

    /**
     * Calculate weights specifically for outgoing servers
     */
    public List<WeightAllocation> calculateOutgoingWeights(List<ServerMetrics> outgoingMetrics) {
        log.debug("Calculating weights for {} outgoing servers", outgoingMetrics.size());
        return calculateWeightsForServerGroup(outgoingMetrics, nginxConfig.getOutgoingServers(), "outgoing");
    }

    /**
     * Generic method to calculate weights for a specific server group
     */
    private List<WeightAllocation> calculateWeightsForServerGroup(
            List<ServerMetrics> metrics,
            List<ServerInfo> servers,
            String groupName) {

        log.debug("Calculating weights for {} {} servers", servers.size(), groupName);

        List<WeightAllocation> allocations = new ArrayList<>();

        if (servers.isEmpty()) {
            log.info("No {} servers configured", groupName);
            return allocations;
        }

        if (metrics.isEmpty()) {
            log.warn("No metrics available for {} servers. Using default weights.", groupName);
            return assignDefaultWeightsForServers(servers);
        }

        List<ServerMetrics> enabledMetrics = metrics.stream()
                .filter(m -> {
                    Optional<ServerInfo> server = servers.stream()
                            .filter(s -> s.getId().equals(m.getServerId()))
                            .findFirst();
                    return server.isPresent() && server.get().isEnabled();
                })
                .toList();

        if (enabledMetrics.isEmpty()) {
            log.warn("All {} servers are disabled. Using default weights.", groupName);
            return assignDefaultWeightsForServers(servers);
        }

        List<WeightScore> scores = new ArrayList<>();
        for (ServerMetrics m : enabledMetrics) {
            WeightScore score = calculateServerScore(m);
            scores.add(score);
            log.debug("{} Server {} - Instant: {}ms, EWMA: {}ms, Raw Score: {:.3f}",
                    groupName,
                    m.getServerId(),
                    String.format("%.2f", m.getAvgResponseTimeMs()),
                    String.format("%.2f", m.getEwmaLatencyMs()),
                    score.getRawScore());
        }

        normalizeAndAssignWeights(scores, allocations);

        // Add disabled servers with 0 weight
        for (ServerMetrics m : metrics) {
            if (enabledMetrics.stream().noneMatch(em -> em.getServerId().equals(m.getServerId()))) {
                Optional<ServerInfo> serverInfo = servers.stream()
                        .filter(s -> s.getId().equals(m.getServerId()))
                        .findFirst();
                if (serverInfo.isPresent()) {
                    WeightAllocation allocation = new WeightAllocation(
                            m.getServerId(),
                            serverInfo.get().getAddress(),
                            0,
                            0.0,
                            "Server manually disabled"
                    );
                    allocations.add(allocation);
                }
            }
        }

        ensureMinimumTraffic(allocations);
        applyFixedWeights(allocations);
        normalizeWeightsToTotal(allocations, 100);

        log.info("{} weight calculation completed. Active servers: {}",
                groupName,
                allocations.stream().filter(WeightAllocation::isActive).count());

        return allocations;
    }

    /**
     * Apply fixed weights from ServerConfiguration
     * This method checks each server's configuration and overrides the calculated weight
     * if a fixed weight is configured
     */
    private void applyFixedWeights(List<WeightAllocation> allocations) {
        for (WeightAllocation allocation : allocations) {
            String serverId = allocation.getServerId();
            Integer calculatedWeight = allocation.getWeight();

            // Get the effective weight (will return fixed weight if configured, otherwise calculated weight)
            Integer effectiveWeight = configService.getEffectiveWeight(serverId, calculatedWeight);

            if (!effectiveWeight.equals(calculatedWeight)) {
                // Weight was overridden by fixed weight configuration
                allocation.setWeight(effectiveWeight);
                allocation.setReason("Fixed weight: " + effectiveWeight + " (Dynamic would be: " + calculatedWeight + ")");
                log.info("Server {} - Applied fixed weight: {} (calculated was: {})",
                        serverId, effectiveWeight, calculatedWeight);
            }
        }
    }

    /**
     * Normalize weights to sum to exactly the target total (usually 100)
     * This ensures proper percentage distribution in split_clients configuration
     *
     * Strategy:
     * 1. Separate fixed-weight servers from dynamic-weight servers
     * 2. Calculate remaining weight for dynamic servers = target - sum(fixed weights)
     * 3. Distribute remaining weight proportionally among dynamic servers
     * 4. Handle edge cases (fixed weights > target, all weights fixed, rounding)
     */
    private void normalizeWeightsToTotal(List<WeightAllocation> allocations, int targetTotal) {
        // Filter active allocations only
        List<WeightAllocation> activeAllocations = allocations.stream()
                .filter(WeightAllocation::isActive)
                .toList();

        if (activeAllocations.isEmpty()) {
            log.warn("No active allocations to normalize");
            return;
        }

        // Separate fixed and dynamic weight allocations
        List<WeightAllocation> fixedWeightAllocations = new ArrayList<>();
        List<WeightAllocation> dynamicWeightAllocations = new ArrayList<>();

        for (WeightAllocation allocation : activeAllocations) {
            String serverId = allocation.getServerId();
            var config = configService.getConfiguration(serverId);

            if (config.isPresent() &&
                    !config.get().getDynamicWeightEnabled() &&
                    config.get().getFixedWeight() != null) {
                fixedWeightAllocations.add(allocation);
            } else {
                dynamicWeightAllocations.add(allocation);
            }
        }

        int totalFixedWeight = fixedWeightAllocations.stream()
                .mapToInt(WeightAllocation::getWeight)
                .sum();

        log.debug("Normalizing weights: {} fixed ({} total), {} dynamic, target = {}",
                fixedWeightAllocations.size(), totalFixedWeight,
                dynamicWeightAllocations.size(), targetTotal);

        // Case 1: Only fixed weights
        if (dynamicWeightAllocations.isEmpty()) {
            if (totalFixedWeight != targetTotal) {
                log.warn("All weights are fixed but sum to {} instead of {}. " +
                        "Normalizing proportionally.", totalFixedWeight, targetTotal);
                normalizeProportionally(fixedWeightAllocations, targetTotal);
            }
            return;
        }

        // Case 2: Fixed weights exceed or equal target
        if (totalFixedWeight >= targetTotal) {
            log.warn("Fixed weights ({}) >= target ({}). Setting dynamic weights to 0 and " +
                    "normalizing fixed weights.", totalFixedWeight, targetTotal);

            // Set all dynamic weights to 0
            for (WeightAllocation allocation : dynamicWeightAllocations) {
                allocation.setWeight(0);
                allocation.setReason(allocation.getReason() + " [Normalized to 0: fixed weights exceed capacity]");
            }

            // Normalize fixed weights to target
            normalizeProportionally(fixedWeightAllocations, targetTotal);
            return;
        }

        // Case 3: Normal case - distribute remaining weight among dynamic servers
        int remainingWeight = targetTotal - totalFixedWeight;

        int totalDynamicWeight = dynamicWeightAllocations.stream()
                .mapToInt(WeightAllocation::getWeight)
                .sum();

        if (totalDynamicWeight == 0) {
            // All dynamic servers have 0 weight - distribute equally
            int weightPerServer = remainingWeight / dynamicWeightAllocations.size();
            int remainder = remainingWeight % dynamicWeightAllocations.size();

            for (int i = 0; i < dynamicWeightAllocations.size(); i++) {
                WeightAllocation allocation = dynamicWeightAllocations.get(i);
                int weight = weightPerServer + (i < remainder ? 1 : 0);
                allocation.setWeight(weight);
                allocation.setReason(allocation.getReason() +
                        String.format(" [Normalized: %d/%d available]", weight, remainingWeight));
            }
        } else {
            // Distribute proportionally based on current weights
            double scaleFactor = (double) remainingWeight / totalDynamicWeight;
            int assignedWeight = 0;

            for (int i = 0; i < dynamicWeightAllocations.size(); i++) {
                WeightAllocation allocation = dynamicWeightAllocations.get(i);
                int originalWeight = allocation.getWeight();

                // For the last server, assign remaining weight to avoid rounding errors
                if (i == dynamicWeightAllocations.size() - 1) {
                    int finalWeight = remainingWeight - assignedWeight;
                    allocation.setWeight(Math.max(0, finalWeight));
                    allocation.setReason(allocation.getReason() +
                            String.format(" [Normalized: %d→%d]", originalWeight, allocation.getWeight()));
                } else {
                    int scaledWeight = (int) Math.round(originalWeight * scaleFactor);
                    allocation.setWeight(scaledWeight);
                    assignedWeight += scaledWeight;
                    allocation.setReason(allocation.getReason() +
                            String.format(" [Normalized: %d→%d]", originalWeight, scaledWeight));
                }
            }
        }

        // Final verification
        int finalTotal = activeAllocations.stream()
                .mapToInt(WeightAllocation::getWeight)
                .sum();

        log.info("Weight normalization complete: {} active servers, total weight = {} (target: {})",
                activeAllocations.size(), finalTotal, targetTotal);

        if (finalTotal != targetTotal) {
            log.warn("Final weight total {} does not match target {}. Difference: {}",
                    finalTotal, targetTotal, finalTotal - targetTotal);
        }
    }

    /**
     * Normalize a list of allocations proportionally to a target total
     * Used when all weights are fixed or when fixed weights exceed capacity
     */
    private void normalizeProportionally(List<WeightAllocation> allocations, int targetTotal) {
        int currentTotal = allocations.stream()
                .mapToInt(WeightAllocation::getWeight)
                .sum();

        if (currentTotal == 0) {
            // Distribute equally
            int weightPerServer = targetTotal / allocations.size();
            int remainder = targetTotal % allocations.size();

            for (int i = 0; i < allocations.size(); i++) {
                WeightAllocation allocation = allocations.get(i);
                int weight = weightPerServer + (i < remainder ? 1 : 0);
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

            // For the last server, assign remaining to avoid rounding errors
            if (i == allocations.size() - 1) {
                int finalWeight = targetTotal - assignedWeight;
                allocation.setWeight(Math.max(1, finalWeight));
                allocation.setReason(allocation.getReason() +
                        String.format(" [Proportionally normalized: %d→%d]", originalWeight, allocation.getWeight()));
            } else {
                int scaledWeight = Math.max(1, (int) Math.round(originalWeight * scaleFactor));
                allocation.setWeight(scaledWeight);
                assignedWeight += scaledWeight;
                allocation.setReason(allocation.getReason() +
                        String.format(" [Proportionally normalized: %d→%d]", originalWeight, scaledWeight));
            }
        }
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
            Optional<ServerInfo> serverInfo = nginxConfig.getServerById(score.getServerId());
            if (!serverInfo.isPresent()) continue;

            double normalizedScore = score.getRawScore() / totalScore;
            int weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT,
                    (int) Math.round(normalizedScore * 100)));

            if (score.getRawScore() < 0.1) {
                weight = 0;
            }

            WeightAllocation allocation = new WeightAllocation(
                    score.getServerId(),
                    serverInfo.get().getAddress(),
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

    /**
     * Assign default weights for all servers (incoming + outgoing)
     */
    private List<WeightAllocation> assignDefaultWeights() {
        List<WeightAllocation> allocations = new ArrayList<>();

        // Add incoming servers
        for (ServerInfo server : nginxConfig.getIncomingServers()) {
            WeightAllocation allocation = new WeightAllocation(
                    server.getId(),
                    server.getAddress(),
                    DEFAULT_WEIGHT,
                    0.5,
                    "Default weight - no metrics available"
            );
            allocations.add(allocation);
        }

        // Add outgoing servers
        for (ServerInfo server : nginxConfig.getOutgoingServers()) {
            WeightAllocation allocation = new WeightAllocation(
                    server.getId(),
                    server.getAddress(),
                    DEFAULT_WEIGHT,
                    0.5,
                    "Default weight - no metrics available"
            );
            allocations.add(allocation);
        }

        log.warn("No metrics available. Assigning default weights to {} servers (incoming + outgoing)",
                allocations.size());

        // Apply fixed weights even for default weights
        applyFixedWeights(allocations);

        // Normalize to ensure sum is 100
        normalizeWeightsToTotal(allocations, 100);

        return allocations;
    }

    /**
     * Assign default weights for a specific list of servers
     */
    private List<WeightAllocation> assignDefaultWeightsForServers(List<ServerInfo> servers) {
        List<WeightAllocation> allocations = new ArrayList<>();

        for (ServerInfo server : servers) {
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

        // Apply fixed weights even for default weights
        applyFixedWeights(allocations);

        // Normalize to ensure sum is 100
        normalizeWeightsToTotal(allocations, 100);

        return allocations;
    }

    private void assignDefaultWeights(List<WeightScore> scores, List<WeightAllocation> allocations) {
        for (WeightScore score : scores) {
            Optional<ServerInfo> serverInfo = nginxConfig.getServerById(score.getServerId());
            if (!serverInfo.isPresent()) continue;

            WeightAllocation allocation = new WeightAllocation(
                    score.getServerId(),
                    serverInfo.get().getAddress(),
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