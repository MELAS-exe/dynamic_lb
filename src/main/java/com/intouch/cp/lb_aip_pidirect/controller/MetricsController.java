package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import com.intouch.cp.lb_aip_pidirect.service.SimulationService;
import com.intouch.cp.lb_aip_pidirect.util.MetricsCalculator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@Slf4j
public class MetricsController {

    private final MetricsCollectionService metricsCollectionService;
    private final MetricsCalculator metricsCalculator;

    @Autowired(required = false)
    private SimulationService simulationService;

    public MetricsController(MetricsCollectionService metricsCollectionService,
                             MetricsCalculator metricsCalculator) {
        this.metricsCollectionService = metricsCollectionService;
        this.metricsCalculator = metricsCalculator;
    }

    /**
     * Endpoint for backend servers to submit their metrics
     */
    @PostMapping("/server/{serverId}")
    public ResponseEntity<Map<String, String>> receiveMetrics(
            @PathVariable String serverId,
            @Valid @RequestBody ServerMetrics metrics) {

        log.info("Received metrics from server: {}", serverId);

        try {
            metricsCollectionService.receiveMetrics(serverId, metrics);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Metrics received and processed");
            response.put("serverId", serverId);
            response.put("timestamp", LocalDateTime.now().toString());
            response.put("instantLatency", String.format("%.2fms", metrics.getAvgResponseTimeMs()));
            response.put("ewmaLatency", String.format("%.2fms", metrics.getEwmaLatencyMs()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing metrics for server {}: {}", serverId, e.getMessage());

            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Failed to process metrics: " + e.getMessage());
            response.put("serverId", serverId);

            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Get latest metrics for all servers
     */
    @GetMapping("/latest")
    public ResponseEntity<List<ServerMetrics>> getLatestMetrics() {
        log.debug("Fetching latest metrics for all servers");

        List<ServerMetrics> metrics = metricsCollectionService.getLatestMetricsForAllServers();
        return ResponseEntity.ok(metrics);
    }

    /**
     * Get metrics for a specific server
     */
    @GetMapping("/server/{serverId}")
    public ResponseEntity<List<ServerMetrics>> getServerMetrics(@PathVariable String serverId) {
        log.debug("Fetching metrics for server: {}", serverId);

        List<ServerMetrics> metrics = metricsCollectionService.getMetricsForServer(serverId);
        return ResponseEntity.ok(metrics);
    }

    /**
     * Get metrics for a server within a time range
     */
    @GetMapping("/server/{serverId}/range")
    public ResponseEntity<List<ServerMetrics>> getServerMetricsInRange(
            @PathVariable String serverId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        log.debug("Fetching metrics for server {} from {} to {}", serverId, start, end);

        List<ServerMetrics> metrics = metricsCollectionService.getMetricsForServerInTimeRange(serverId, start, end);
        return ResponseEntity.ok(metrics);
    }

    /**
     * Get metrics analysis for a server (including EWMA analysis)
     */
    @GetMapping("/server/{serverId}/analysis")
    public ResponseEntity<Map<String, Object>> getServerAnalysis(@PathVariable String serverId) {
        log.debug("Generating analysis for server: {}", serverId);

        List<ServerMetrics> metrics = metricsCollectionService.getMetricsForServer(serverId);

        if (metrics.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> analysis = new HashMap<>();

        // Basic statistics
        analysis.put("totalRecords", metrics.size());
        analysis.put("avgEwmaLatency", metricsCalculator.calculateAverageResponseTime(metrics));
        analysis.put("avgErrorRate", metricsCalculator.calculateAverageErrorRate(metrics));
        analysis.put("avgSuccessRate", metricsCalculator.calculateAverageSuccessRate(metrics));

        // Trends
        analysis.put("latencyTrend", metricsCalculator.calculateResponseTimeTrend(metrics));
        analysis.put("requestVolumeTrend", metricsCalculator.calculateRequestVolumeTrend(metrics));

        // EWMA specific analysis
        if (metrics.size() >= 3) {
            analysis.put("ewmaSmoothness", metricsCalculator.calculateEwmaSmoothness(metrics));
        }
        
        // Health scores
        if (!metrics.isEmpty()) {
            ServerMetrics latest = metrics.get(0);
            analysis.put("latestHealthScore", metricsCalculator.calculateCompositeHealthScore(latest));
            analysis.put("stabilityScore", metricsCalculator.calculateStabilityScore(metrics));
            analysis.put("latencyConsistency", metricsCalculator.calculateLatencyConsistency(latest));
            analysis.put("isDegrading", metricsCalculator.isServerDegrading(metrics));
            analysis.put("isMetricsFresh", metricsCalculator.isMetricsFresh(latest, 5));

            // EWMA details
            Map<String, Object> ewmaDetails = new HashMap<>();
            ewmaDetails.put("instantLatency", latest.getAvgResponseTimeMs());
            ewmaDetails.put("ewmaLatency", latest.getEwmaLatencyMs());
            ewmaDetails.put("smoothingFactor", latest.getEwmaAlpha());
            if (latest.getEwmaLatencyMs() != null && latest.getAvgResponseTimeMs() != null) {
                double variance = Math.abs(latest.getEwmaLatencyMs() - latest.getAvgResponseTimeMs());
                ewmaDetails.put("varianceFromInstant", variance);
                ewmaDetails.put("variancePercentage",
                        (variance / latest.getAvgResponseTimeMs()) * 100);
            }
            analysis.put("ewmaDetails", ewmaDetails);
        }

        return ResponseEntity.ok(analysis);
    }

    /**
     * Get EWMA comparison for a server
     */
    @GetMapping("/server/{serverId}/ewma-comparison")
    public ResponseEntity<Map<String, Object>> getEwmaComparison(@PathVariable String serverId) {
        log.debug("Generating EWMA comparison for server: {}", serverId);

        List<ServerMetrics> metrics = metricsCollectionService.getMetricsForServer(serverId);

        if (metrics.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> comparison = new HashMap<>();

        List<Map<String, Object>> dataPoints = metrics.stream()
                .limit(20) // Last 20 data points
                .map(m -> {
                    Map<String, Object> point = new HashMap<>();
                    point.put("timestamp", m.getCreatedAt());
                    point.put("instantLatency", m.getAvgResponseTimeMs());
                    point.put("ewmaLatency", m.getEwmaLatencyMs());
                    point.put("difference",
                            m.getEwmaLatencyMs() != null && m.getAvgResponseTimeMs() != null
                                    ? Math.abs(m.getEwmaLatencyMs() - m.getAvgResponseTimeMs())
                                    : null);
                    return point;
                })
                .toList();

        comparison.put("dataPoints", dataPoints);
        comparison.put("smoothnessScore", metricsCalculator.calculateEwmaSmoothness(metrics));
        comparison.put("serverId", serverId);

        return ResponseEntity.ok(comparison);
    }

    /**
     * Get overall system metrics summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSystemSummary() {
        log.debug("Generating system metrics summary");

        List<ServerMetrics> allMetrics = metricsCollectionService.getLatestMetricsForAllServers();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalServers", allMetrics.size());
        summary.put("totalMetricsRecords", metricsCollectionService.getMetricsCount());

        if (!allMetrics.isEmpty()) {
            // Calculate system-wide averages using EWMA
            double avgEwmaLatency = allMetrics.stream()
                    .filter(m -> m.getEffectiveLatency() != null)
                    .mapToDouble(ServerMetrics::getEffectiveLatency)
                    .average()
                    .orElse(0.0);

            double avgErrorRate = allMetrics.stream()
                    .filter(m -> m.getErrorRatePercentage() != null)
                    .mapToDouble(ServerMetrics::getErrorRatePercentage)
                    .average()
                    .orElse(0.0);

            double avgSuccessRate = allMetrics.stream()
                    .filter(m -> m.getSuccessRatePercentage() != null)
                    .mapToDouble(ServerMetrics::getSuccessRatePercentage)
                    .average()
                    .orElse(0.0);

            double avgUptime = allMetrics.stream()
                    .filter(m -> m.getUptimePercentage() != null)
                    .mapToDouble(ServerMetrics::getUptimePercentage)
                    .average()
                    .orElse(0.0);

            long healthyServers = allMetrics.stream()
                    .filter(ServerMetrics::isHealthy)
                    .count();

            summary.put("systemAvgEwmaLatency", avgEwmaLatency);
            summary.put("systemAvgErrorRate", avgErrorRate);
            summary.put("systemAvgSuccessRate", avgSuccessRate);
            summary.put("systemAvgUptime", avgUptime);
            summary.put("healthyServers", healthyServers);
            summary.put("unhealthyServers", allMetrics.size() - healthyServers);

            // Fresh metrics check
            long freshMetrics = allMetrics.stream()
                    .filter(m -> metricsCalculator.isMetricsFresh(m, 5))
                    .count();
            summary.put("serversWithFreshMetrics", freshMetrics);
            summary.put("serversWithStaleMetrics", allMetrics.size() - freshMetrics);

            // EWMA system-wide analysis
            Map<String, Object> ewmaSystemStats = new HashMap<>();
            double avgInstantLatency = allMetrics.stream()
                    .filter(m -> m.getAvgResponseTimeMs() != null)
                    .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
                    .average()
                    .orElse(0.0);

            ewmaSystemStats.put("avgInstantLatency", avgInstantLatency);
            ewmaSystemStats.put("avgEwmaLatency", avgEwmaLatency);
            ewmaSystemStats.put("smoothingEffect",
                    Math.abs(avgEwmaLatency - avgInstantLatency));
            summary.put("ewmaSystemStats", ewmaSystemStats);
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("timestamp", LocalDateTime.now());
        health.put("service", "metrics-controller");
        health.put("simulationEnabled", simulationService != null);

        try {
            long totalMetrics = metricsCollectionService.getMetricsCount();
            health.put("totalMetricsRecords", totalMetrics);
            health.put("dbConnection", "ok");
        } catch (Exception e) {
            health.put("dbConnection", "error: " + e.getMessage());
        }

        return ResponseEntity.ok(health);
    }
}