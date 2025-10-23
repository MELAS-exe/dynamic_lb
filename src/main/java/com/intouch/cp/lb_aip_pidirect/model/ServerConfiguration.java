package com.intouch.cp.lb_aip_pidirect.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "server_configuration")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String serverId;

    // Weight configuration
    @Column(name = "dynamic_weight_enabled")
    private Boolean dynamicWeightEnabled = true;

    @Column(name = "fixed_weight")
    private Integer fixedWeight;

    // Thresholds for automatic removal
    @Column(name = "max_response_time_ms")
    private Double maxResponseTimeMs;

    @Column(name = "max_error_rate_percentage")
    private Double maxErrorRatePercentage;

    @Column(name = "min_success_rate_percentage")
    private Double minSuccessRatePercentage;

    @Column(name = "max_timeout_rate_percentage")
    private Double maxTimeoutRatePercentage;

    @Column(name = "min_uptime_percentage")
    private Double minUptimePercentage;

    // Threshold violation tracking
    @Column(name = "threshold_violations_count")
    private Integer thresholdViolationsCount = 0;

    @Column(name = "max_violations_before_removal")
    private Integer maxViolationsBeforeRemoval = 3;

    @Column(name = "auto_removal_enabled")
    private Boolean autoRemovalEnabled = false;

    // Status tracking
    @Column(name = "manually_removed")
    private Boolean manuallyRemoved = false;

    @Column(name = "last_threshold_violation")
    private LocalDateTime lastThresholdViolation;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public ServerConfiguration(String serverId) {
        this.serverId = serverId;
        this.dynamicWeightEnabled = true;
        this.autoRemovalEnabled = false;
        this.thresholdViolationsCount = 0;
        this.maxViolationsBeforeRemoval = 3;
        this.manuallyRemoved = false;
    }

    /**
     * Check if metrics violate any configured threshold
     */
    public boolean violatesThresholds(ServerMetrics metrics) {
        if (!autoRemovalEnabled) {
            return false;
        }

        boolean violated = false;

        if (maxResponseTimeMs != null && metrics.getEffectiveLatency() != null) {
            if (metrics.getEffectiveLatency() > maxResponseTimeMs) {
                violated = true;
            }
        }

        if (maxErrorRatePercentage != null && metrics.getErrorRatePercentage() != null) {
            if (metrics.getErrorRatePercentage() > maxErrorRatePercentage) {
                violated = true;
            }
        }

        if (minSuccessRatePercentage != null && metrics.getSuccessRatePercentage() != null) {
            if (metrics.getSuccessRatePercentage() < minSuccessRatePercentage) {
                violated = true;
            }
        }

        if (maxTimeoutRatePercentage != null && metrics.getTimeoutRatePercentage() != null) {
            if (metrics.getTimeoutRatePercentage() > maxTimeoutRatePercentage) {
                violated = true;
            }
        }

        if (minUptimePercentage != null && metrics.getUptimePercentage() != null) {
            if (metrics.getUptimePercentage() < minUptimePercentage) {
                violated = true;
            }
        }

        return violated;
    }

    /**
     * Record a threshold violation
     */
    public void recordViolation() {
        this.thresholdViolationsCount++;
        this.lastThresholdViolation = LocalDateTime.now();
    }

    /**
     * Reset violation count
     */
    public void resetViolations() {
        this.thresholdViolationsCount = 0;
        this.lastThresholdViolation = null;
    }

    /**
     * Check if server should be removed based on violations
     */
    public boolean shouldBeRemoved() {
        return autoRemovalEnabled &&
                thresholdViolationsCount >= maxViolationsBeforeRemoval;
    }

    /**
     * Get violation details
     */
    public String getViolationDetails(ServerMetrics metrics) {
        StringBuilder details = new StringBuilder();

        if (maxResponseTimeMs != null && metrics.getEffectiveLatency() != null) {
            if (metrics.getEffectiveLatency() > maxResponseTimeMs) {
                details.append(String.format("Response time %.2fms exceeds max %.2fms; ",
                        metrics.getEffectiveLatency(), maxResponseTimeMs));
            }
        }

        if (maxErrorRatePercentage != null && metrics.getErrorRatePercentage() != null) {
            if (metrics.getErrorRatePercentage() > maxErrorRatePercentage) {
                details.append(String.format("Error rate %.2f%% exceeds max %.2f%%; ",
                        metrics.getErrorRatePercentage(), maxErrorRatePercentage));
            }
        }

        if (minSuccessRatePercentage != null && metrics.getSuccessRatePercentage() != null) {
            if (metrics.getSuccessRatePercentage() < minSuccessRatePercentage) {
                details.append(String.format("Success rate %.2f%% below min %.2f%%; ",
                        metrics.getSuccessRatePercentage(), minSuccessRatePercentage));
            }
        }

        if (maxTimeoutRatePercentage != null && metrics.getTimeoutRatePercentage() != null) {
            if (metrics.getTimeoutRatePercentage() > maxTimeoutRatePercentage) {
                details.append(String.format("Timeout rate %.2f%% exceeds max %.2f%%; ",
                        metrics.getTimeoutRatePercentage(), maxTimeoutRatePercentage));
            }
        }

        if (minUptimePercentage != null && metrics.getUptimePercentage() != null) {
            if (metrics.getUptimePercentage() < minUptimePercentage) {
                details.append(String.format("Uptime %.2f%% below min %.2f%%; ",
                        metrics.getUptimePercentage(), minUptimePercentage));
            }
        }

        return details.toString();
    }
}