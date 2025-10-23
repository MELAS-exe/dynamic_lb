package com.intouch.cp.lb_aip_pidirect.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class WeightRecalculationService {

    private final MetricsCollectionService metricsCollectionService;
    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;

    // Use @Lazy to avoid circular dependency
    public WeightRecalculationService(
            @Lazy MetricsCollectionService metricsCollectionService,
            WeightCalculationService weightCalculationService,
            NginxConfigService nginxConfigService) {
        this.metricsCollectionService = metricsCollectionService;
        this.weightCalculationService = weightCalculationService;
        this.nginxConfigService = nginxConfigService;
    }

    /**
     * Trigger full weight recalculation and NGINX update
     */
    public void triggerWeightRecalculation() {
        try {
            log.info("Triggering weight recalculation");

            // Get latest metrics
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();

            if (latestMetrics.isEmpty()) {
                log.warn("No metrics available for weight recalculation");
                return;
            }

            // Calculate new weights (will use fixed weights where configured)
            var weights = weightCalculationService.calculateWeights(latestMetrics);

            // Update NGINX configuration
            nginxConfigService.updateUpstreamConfiguration(weights);

            log.info("Weight recalculation completed successfully. Active servers: {}",
                    weights.stream().filter(w -> w.isActive()).count());

        } catch (Exception e) {
            log.error("Error during weight recalculation: {}", e.getMessage(), e);
        }
    }

    /**
     * Trigger recalculation with delay (useful for batch updates)
     */
    public void triggerWeightRecalculationAsync() {
        new Thread(() -> {
            try {
                Thread.sleep(500); // Small delay to batch multiple config changes
                triggerWeightRecalculation();
            } catch (InterruptedException e) {
                log.warn("Weight recalculation interrupted");
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}