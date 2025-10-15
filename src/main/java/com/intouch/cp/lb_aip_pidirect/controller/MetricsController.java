package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import com.intouch.cp.lb_aip_pidirect.service.SimulationService;
import com.intouch.cp.lb_aip_pidirect.util.MetricsCalculator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    // Make SimulationService optional
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
     * Get metrics analysis for a server
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
        analysis.put("avgResponseTime", metricsCalculator.calculateAverageResponseTime(metrics));
        analysis.put("avgErrorRate", metricsCalculator.calculateAverageErrorRate(metrics));

        // Trends
        analysis.put("responseTimeTrend", metricsCalculator.calculateResponseTimeTrend(metrics));
        analysis.put("requestVolumeTrend", metricsCalculator.calculateRequestVolumeTrend(metrics));

        // Health scores
        if (!metrics.isEmpty()) {
            ServerMetrics latest = metrics.get(0);
            analysis.put("latestHealthScore", metricsCalculator.calculateCompositeHealthScore(latest));
            analysis.put("stabilityScore", metricsCalculator.calculateStabilityScore(metrics));
            analysis.put("latencyConsistency", metricsCalculator.calculateLatencyConsistency(latest));
            analysis.put("isDegrading", metricsCalculator.isServerDegrading(metrics));
            analysis.put("isMetricsFresh", metricsCalculator.isMetricsFresh(latest, 5));
        }

        return ResponseEntity.ok(analysis);
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
            // Calculate system-wide averages
            double avgResponseTime = allMetrics.stream()
                    .filter(m -> m.getAvgResponseTimeMs() != null)
                    .mapToDouble(ServerMetrics::getAvgResponseTimeMs)
                    .average()
                    .orElse(0.0);

            double avgErrorRate = allMetrics.stream()
                    .filter(m -> m.getErrorRatePercentage() != null)
                    .mapToDouble(ServerMetrics::getErrorRatePercentage)
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

            summary.put("systemAvgResponseTime", avgResponseTime);
            summary.put("systemAvgErrorRate", avgErrorRate);
            summary.put("systemAvgUptime", avgUptime);
            summary.put("healthyServers", healthyServers);
            summary.put("unhealthyServers", allMetrics.size() - healthyServers);

            // Fresh metrics check
            long freshMetrics = allMetrics.stream()
                    .filter(m -> metricsCalculator.isMetricsFresh(m, 5))
                    .count();
            summary.put("serversWithFreshMetrics", freshMetrics);
            summary.put("serversWithStaleMetrics", allMetrics.size() - freshMetrics);
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * Simulation endpoints - only available when SimulationService is enabled
     */
    @PostMapping("/simulation/server1/degrade")
    public ResponseEntity<Map<String, String>> simulateServer1Degradation() {
        if (simulationService == null) {
            return createSimulationDisabledResponse();
        }

        simulationService.simulateServer1Degradation();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Server1 degradation simulation activated");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/simulation/server2/errors")
    public ResponseEntity<Map<String, String>> simulateServer2HighErrors() {
        if (simulationService == null) {
            return createSimulationDisabledResponse();
        }

        simulationService.simulateServer2HighErrors();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Server2 high error simulation activated");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/simulation/reset")
    public ResponseEntity<Map<String, String>> resetSimulation() {
        if (simulationService == null) {
            return createSimulationDisabledResponse();
        }

        simulationService.resetAllSimulations();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "All simulations reset - servers healthy");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/simulation/random")
    public ResponseEntity<Map<String, String>> triggerRandomSimulation() {
        if (simulationService == null) {
            return createSimulationDisabledResponse();
        }

        simulationService.simulateRandomScenario();

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Random simulation scenario triggered");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/simulation/status")
    public ResponseEntity<Map<String, Object>> getSimulationStatus() {
        if (simulationService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "disabled");
            response.put("message", "Simulation service is not enabled");
            return ResponseEntity.ok(response);
        }

        String status = simulationService.getCurrentSimulationStatus();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "enabled");
        response.put("simulationStatus", status);

        return ResponseEntity.ok(response);
    }

    /**
     * Submit custom metrics for testing
     */
    @PostMapping("/server/{serverId}/custom")
    public ResponseEntity<Map<String, String>> submitCustomMetrics(
            @PathVariable String serverId,
            @RequestParam double responseTime,
            @RequestParam double errorRate,
            @RequestParam double timeoutRate,
            @RequestParam double uptime) {

        if (simulationService == null) {
            return createSimulationDisabledResponse();
        }

        ServerMetrics customMetrics = simulationService.generateSpecificMetrics(
                serverId, responseTime, errorRate, timeoutRate, uptime);

        metricsCollectionService.receiveMetrics(serverId, customMetrics);

        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Custom metrics submitted");
        response.put("serverId", serverId);

        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, String>> createSimulationDisabledResponse() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "disabled");
        response.put("message", "Simulation service is not enabled. Set 'loadbalancer.simulation.enabled=true' to enable simulation features.");

        return ResponseEntity.badRequest().body(response);
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