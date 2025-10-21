package com.intouch.cp.lb_aip_pidirect.util;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class MetricsCalculator {

    /**
     * Calculate average EWMA latency for a list of metrics
     */
    public double calculateAverageResponseTime(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }

        return metrics.stream()
                .filter(m -> m.getEffectiveLatency() != null)
                .mapToDouble(ServerMetrics::getEffectiveLatency)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate average error rate for a list of metrics
     */
    public double calculateAverageErrorRate(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }

        return metrics.stream()
                .filter(m -> m.getErrorRatePercentage() != null)
                .mapToDouble(ServerMetrics::getErrorRatePercentage)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate average success rate for a list of metrics
     */
    public double calculateAverageSuccessRate(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }

        return metrics.stream()
                .filter(m -> m.getSuccessRatePercentage() != null)
                .mapToDouble(ServerMetrics::getSuccessRatePercentage)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate trend for EWMA latency (positive = getting worse, negative = improving)
     */
    public double calculateResponseTimeTrend(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 2) {
            return 0.0;
        }

        List<ServerMetrics> sortedMetrics = metrics.stream()
                .filter(m -> m.getEffectiveLatency() != null && m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 2) {
            return 0.0;
        }

        int n = sortedMetrics.size();
        double recent = sortedMetrics.subList(n/2, n).stream()
                .mapToDouble(ServerMetrics::getEffectiveLatency)
                .average()
                .orElse(0.0);

        double older = sortedMetrics.subList(0, n/2).stream()
                .mapToDouble(ServerMetrics::getEffectiveLatency)
                .average()
                .orElse(0.0);

        if (older == 0) return 0.0;

        return ((recent - older) / older) * 100;
    }

    /**
     * Calculate server stability score based on variance in EWMA latency
     */
    public double calculateStabilityScore(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 3) {
            return 0.5;
        }

        double avgResponseTime = calculateAverageResponseTime(metrics);
        if (avgResponseTime == 0) {
            return 0.0;
        }

        double variance = metrics.stream()
                .filter(m -> m.getEffectiveLatency() != null)
                .mapToDouble(m -> Math.pow(m.getEffectiveLatency() - avgResponseTime, 2))
                .average()
                .orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / avgResponseTime;

        return Math.max(0.0, Math.min(1.0, 1.0 - (coefficientOfVariation / 0.5)));
    }

    /**
     * Calculate latency consistency score based on P99/P50 ratio
     */
    public double calculateLatencyConsistency(ServerMetrics metrics) {
        if (metrics == null || metrics.getLatencyP50() == null || metrics.getLatencyP99() == null) {
            return 0.5;
        }

        if (metrics.getLatencyP50() == 0) {
            return 0.0;
        }

        double ratio = (double) metrics.getLatencyP99() / metrics.getLatencyP50();

        if (ratio <= 2.0) {
            return 1.0;
        } else if (ratio >= 10.0) {
            return 0.0;
        } else {
            return 1.0 - ((ratio - 2.0) / 8.0);
        }
    }

    /**
     * Calculate composite health score
     */
    public double calculateCompositeHealthScore(ServerMetrics metrics) {
        if (metrics == null) {
            return 0.0;
        }

        double responseTimeScore = calculateResponseTimeScore(metrics.getEffectiveLatency());
        double errorRateScore = calculateErrorRateScore(metrics.getErrorRatePercentage());
        double successRateScore = calculateSuccessRateScore(metrics.getSuccessRatePercentage());
        double timeoutRateScore = calculateTimeoutRateScore(metrics.getTimeoutRatePercentage());
        double uptimeScore = calculateUptimeScore(metrics.getUptimePercentage());
        double latencyConsistencyScore = calculateLatencyConsistency(metrics);

        return (responseTimeScore * 0.20) +
                (errorRateScore * 0.20) +
                (successRateScore * 0.15) +
                (timeoutRateScore * 0.15) +
                (uptimeScore * 0.20) +
                (latencyConsistencyScore * 0.10);
    }

    /**
     * Check if metrics are considered fresh (recent)
     */
    public boolean isMetricsFresh(ServerMetrics metrics, int maxAgeMinutes) {
        if (metrics == null || metrics.getCreatedAt() == null) {
            return false;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes);
        return metrics.getCreatedAt().isAfter(cutoff);
    }

    /**
     * Calculate request volume trend
     */
    public double calculateRequestVolumeTrend(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 2) {
            return 0.0;
        }

        List<ServerMetrics> sortedMetrics = metrics.stream()
                .filter(m -> m.getRequestsPerMinute() != null && m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 2) {
            return 0.0;
        }

        int n = sortedMetrics.size();
        double recentAvg = sortedMetrics.subList(n/2, n).stream()
                .mapToDouble(m -> m.getRequestsPerMinute().doubleValue())
                .average()
                .orElse(0.0);

        double olderAvg = sortedMetrics.subList(0, n/2).stream()
                .mapToDouble(m -> m.getRequestsPerMinute().doubleValue())
                .average()
                .orElse(0.0);

        if (olderAvg == 0) {
            return 0.0;
        }

        return ((recentAvg - olderAvg) / olderAvg) * 100;
    }

    /**
     * Detect if server is experiencing degradation
     */
    public boolean isServerDegrading(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 3) {
            return false;
        }

        double responseTimeTrend = calculateResponseTimeTrend(metrics);
        double errorRateTrend = calculateErrorRateTrend(metrics);

        return responseTimeTrend > 20.0 || errorRateTrend > 100.0;
    }

    /**
     * Calculate EWMA smoothness score
     */
    public double calculateEwmaSmoothness(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 3) {
            return 0.5;
        }

        List<ServerMetrics> sortedMetrics = metrics.stream()
                .filter(m -> m.getAvgResponseTimeMs() != null && m.getEwmaLatencyMs() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 3) {
            return 0.5;
        }

        double instantVariance = calculateVariance(
                sortedMetrics.stream()
                        .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
                        .toArray()
        );

        double ewmaVariance = calculateVariance(
                sortedMetrics.stream()
                        .mapToDouble(ServerMetrics::getEwmaLatencyMs)
                        .toArray()
        );

        if (instantVariance == 0) return 1.0;

        double smoothnessRatio = 1.0 - (ewmaVariance / instantVariance);
        return Math.max(0.0, Math.min(1.0, smoothnessRatio));
    }

    private double calculateVariance(double[] values) {
        if (values.length < 2) return 0.0;

        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;

        double variance = 0.0;
        for (double value : values) {
            variance += Math.pow(value - mean, 2);
        }
        return variance / values.length;
    }

    private double calculateErrorRateTrend(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 2) {
            return 0.0;
        }

        List<ServerMetrics> sortedMetrics = metrics.stream()
                .filter(m -> m.getErrorRatePercentage() != null && m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 2) {
            return 0.0;
        }

        int n = sortedMetrics.size();
        double recent = sortedMetrics.subList(n/2, n).stream()
                .mapToDouble(ServerMetrics::getErrorRatePercentage)
                .average()
                .orElse(0.0);

        double older = sortedMetrics.subList(0, n/2).stream()
                .mapToDouble(ServerMetrics::getErrorRatePercentage)
                .average()
                .orElse(0.0);

        if (older == 0) {
            return recent > 0 ? 100.0 : 0.0;
        }

        return ((recent - older) / older) * 100;
    }

    private double calculateResponseTimeScore(Double responseTime) {
        if (responseTime == null || responseTime <= 0) return 0.0;
        if (responseTime <= 200) return 1.0;
        if (responseTime <= 500) return 1.0 - ((responseTime - 200) / 300) * 0.5;
        if (responseTime <= 1000) return 0.5 - ((responseTime - 500) / 500) * 0.4;
        return Math.max(0.0, 0.1 - ((responseTime - 1000) / 2000) * 0.1);
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

    private double calculateTimeoutRateScore(Double timeoutRate) {
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
}