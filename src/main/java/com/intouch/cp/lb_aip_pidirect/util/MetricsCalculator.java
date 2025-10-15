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
     * Calculate average response time for a list of metrics
     */
    public double calculateAverageResponseTime(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return 0.0;
        }

        return metrics.stream()
                .filter(m -> m.getAvgResponseTimeMs() != null)
                .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
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
     * Calculate trend for response time (positive = getting worse, negative = improving)
     */
    public double calculateResponseTimeTrend(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 2) {
            return 0.0;
        }

        // Sort by creation time
        List<ServerMetrics> sortedMetrics = metrics.stream()
                .filter(m -> m.getAvgResponseTimeMs() != null && m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 2) {
            return 0.0;
        }

        // Simple linear trend calculation
        int n = sortedMetrics.size();
        double recent = sortedMetrics.subList(n/2, n).stream()
                .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
                .average()
                .orElse(0.0);

        double older = sortedMetrics.subList(0, n/2).stream()
                .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
                .average()
                .orElse(0.0);

        return ((recent - older) / older) * 100; // Percentage change
    }

    /**
     * Calculate server stability score based on variance in metrics
     */
    public double calculateStabilityScore(List<ServerMetrics> metrics) {
        if (metrics == null || metrics.size() < 3) {
            return 0.5; // Neutral score for insufficient data
        }

        // Calculate coefficient of variation for response time
        double avgResponseTime = calculateAverageResponseTime(metrics);
        if (avgResponseTime == 0) {
            return 0.0;
        }

        double variance = metrics.stream()
                .filter(m -> m.getAvgResponseTimeMs() != null)
                .mapToDouble(m -> Math.pow(m.getAvgResponseTimeMs() - avgResponseTime, 2))
                .average()
                .orElse(0.0);

        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / avgResponseTime;

        // Convert to stability score (0-1, higher is more stable)
        // CV < 0.1 = very stable (score 1.0)
        // CV > 0.5 = very unstable (score 0.0)
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

        // Good consistency: P99/P50 ratio < 2.0 (score 1.0)
        // Poor consistency: P99/P50 ratio > 10.0 (score 0.0)
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

        double responseTimeScore = calculateResponseTimeScore(metrics.getAvgResponseTimeMs());
        double errorRateScore = calculateErrorRateScore(metrics.getErrorRatePercentage());
        double timeoutRateScore = calculateTimeoutRateScore(metrics.getTimeoutRatePercentage());
        double uptimeScore = calculateUptimeScore(metrics.getUptimePercentage());
        double latencyConsistencyScore = calculateLatencyConsistency(metrics);

        // Weighted average
        return (responseTimeScore * 0.25) +
                (errorRateScore * 0.25) +
                (timeoutRateScore * 0.15) +
                (uptimeScore * 0.25) +
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
                .filter(m -> m.getRequestPerMinute() != null && m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        if (sortedMetrics.size() < 2) {
            return 0.0;
        }

        int n = sortedMetrics.size();
        double recentAvg = sortedMetrics.subList(n/2, n).stream()
                .mapToDouble(m -> m.getRequestPerMinute().doubleValue())
                .average()
                .orElse(0.0);

        double olderAvg = sortedMetrics.subList(0, n/2).stream()
                .mapToDouble(m -> m.getRequestPerMinute().doubleValue())
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

        // Server is degrading if response time increased by >20% or error rate increased by >100%
        return responseTimeTrend > 20.0 || errorRateTrend > 100.0;
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