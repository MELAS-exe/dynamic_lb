package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "loadbalancer.simulation.enabled", havingValue = "true")
public class SimulationService {

    private final NginxConfig nginxConfig;
    private final MetricsCollectionService metricsCollectionService;
    private final Random random = new Random();

    private volatile boolean server1Degraded = false;
    private volatile boolean server2HighErrors = false;
    private volatile int simulationCycle = 0;

    @Scheduled(fixedRateString = "#{${loadbalancer.simulation.interval-seconds:60} * 1000}")
    public void generateSimulatedMetrics() {
        if (!nginxConfig.getSimulation().getEnabled()) {
            return;
        }

        try {
            log.debug("Generating simulated metrics for cycle: {}", simulationCycle);

            for (var server : nginxConfig.getServers()) {
                ServerMetrics metrics = generateMetricsForServer(server.getId());
                metricsCollectionService.receiveMetrics(server.getId(), metrics);
            }

            if (simulationCycle % 5 == 0) {
                updateSimulationScenario();
            }

            simulationCycle++;

        } catch (Exception e) {
            log.error("Error generating simulated metrics: {}", e.getMessage(), e);
        }
    }

    private ServerMetrics generateMetricsForServer(String serverId) {
        ServerMetrics metrics = new ServerMetrics();
        metrics.setServerId(serverId);
        metrics.setWindowTimestamp(Instant.now().getEpochSecond());

        // Base metrics (healthy server)
        double baseResponseTime = 200.0;
        double baseErrorRate = 0.5;
        double baseSuccessRate = 99.5;
        double baseTimeoutRate = 0.1;
        double baseUptime = 99.5;
        int baseRequestsPerMinute = 100;

        // Apply server-specific scenarios
        switch (serverId) {
            case "server1":
                if (server1Degraded) {
                    baseResponseTime = 800.0;
                    baseUptime = 95.0;
                    baseSuccessRate = 95.0;
                    baseErrorRate = 5.0;
                    log.debug("Server1 simulating degraded performance");
                }
                break;

            case "server2":
                if (server2HighErrors) {
                    baseErrorRate = 8.0;
                    baseSuccessRate = 92.0;
                    baseTimeoutRate = 3.0;
                    baseUptime = 92.0;
                    log.debug("Server2 simulating high error rate");
                }
                break;

            case "server3":
                if (random.nextDouble() < 0.05) {
                    baseUptime = 85.0;
                    baseErrorRate = 15.0;
                    baseSuccessRate = 85.0;
                    baseResponseTime = 2000.0;
                    log.debug("Server3 simulating brief outage");
                }
                break;
        }

        // Add random variation
        double variation = nginxConfig.getSimulation().getMetricsVariation();

        metrics.setAvgResponseTimeMs(addVariation(baseResponseTime, variation));
        metrics.setErrorRatePercentage(Math.max(0.0, Math.min(100.0, addVariation(baseErrorRate, variation))));
        metrics.setSuccessRatePercentage(Math.max(0.0, Math.min(100.0, addVariation(baseSuccessRate, variation))));
        metrics.setTimeoutRatePercentage(Math.max(0.0, Math.min(100.0, addVariation(baseTimeoutRate, variation))));
        metrics.setUptimePercentage(Math.max(0.0, Math.min(100.0, addVariation(baseUptime, variation))));
        metrics.setRequestsPerMinute((int) Math.max(1, addVariation(baseRequestsPerMinute, variation * 0.5)));

        // Generate latency percentiles based on average response time
        double avgMs = metrics.getAvgResponseTimeMs();
        metrics.setLatencyP50((int) (avgMs * 0.8));
        metrics.setLatencyP95((int) (avgMs * 1.5));
        metrics.setLatencyP99((int) (avgMs * 2.0));

        return metrics;
    }

    private void updateSimulationScenario() {
        double scenario = random.nextDouble();

        if (scenario < 0.3) {
            server1Degraded = true;
            server2HighErrors = false;
            log.info("=== SIMULATION: Server1 experiencing performance degradation ===");

        } else if (scenario < 0.6) {
            server1Degraded = false;
            server2HighErrors = true;
            log.info("=== SIMULATION: Server2 experiencing high error rates ===");

        } else if (scenario < 0.8) {
            server1Degraded = false;
            server2HighErrors = false;
            log.info("=== SIMULATION: All servers healthy ===");

        } else {
            server1Degraded = true;
            server2HighErrors = true;
            log.info("=== SIMULATION: Multiple servers experiencing issues ===");
        }
    }

    private double addVariation(double base, double variationPercent) {
        double variation = base * variationPercent;
        return base + (random.nextGaussian() * variation);
    }

    public void simulateServer1Degradation() {
        server1Degraded = true;
        log.info("Manually triggered Server1 degradation simulation");
    }

    public void simulateServer2HighErrors() {
        server2HighErrors = true;
        log.info("Manually triggered Server2 high error simulation");
    }

    public void resetAllSimulations() {
        server1Degraded = false;
        server2HighErrors = false;
        log.info("Reset all simulation scenarios - all servers healthy");
    }

    public void simulateRandomScenario() {
        updateSimulationScenario();
    }

    public ServerMetrics generateSpecificMetrics(String serverId,
                                                 double responseTime,
                                                 double errorRate,
                                                 double timeoutRate,
                                                 double uptime) {
        ServerMetrics metrics = new ServerMetrics();
        metrics.setServerId(serverId);
        metrics.setWindowTimestamp(Instant.now().getEpochSecond());
        metrics.setAvgResponseTimeMs(responseTime);
        metrics.setErrorRatePercentage(errorRate);
        metrics.setSuccessRatePercentage(100.0 - errorRate);
        metrics.setTimeoutRatePercentage(timeoutRate);
        metrics.setUptimePercentage(uptime);
        metrics.setRequestsPerMinute(ThreadLocalRandom.current().nextInt(50, 150));

        // Generate realistic latency percentiles
        metrics.setLatencyP50((int) (responseTime * 0.8));
        metrics.setLatencyP95((int) (responseTime * 1.5));
        metrics.setLatencyP99((int) (responseTime * 2.0));

        return metrics;
    }

    public String getCurrentSimulationStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Simulation Cycle: ").append(simulationCycle).append("\n");
        status.append("Server1 Degraded: ").append(server1Degraded).append("\n");
        status.append("Server2 High Errors: ").append(server2HighErrors).append("\n");
        status.append("Simulation Enabled: ").append(nginxConfig.getSimulation().getEnabled()).append("\n");
        status.append("Interval: ").append(nginxConfig.getSimulation().getIntervalSeconds()).append(" seconds\n");
        return status.toString();
    }
}