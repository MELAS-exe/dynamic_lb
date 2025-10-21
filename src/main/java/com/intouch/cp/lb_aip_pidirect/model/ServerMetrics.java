package com.intouch.cp.lb_aip_pidirect.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "server_metrics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String serverId;

    // Latence instantanée reçue du serveur
    @JsonProperty("avg_response_time_ms")
    @Column(name = "avg_response_time_ms")
    private Double avgResponseTimeMs;

    // Latence calculée avec EWMA (stockée)
    @Column(name = "ewma_latency_ms")
    private Double ewmaLatencyMs;

    // Alpha pour le calcul EWMA (par défaut 0.3)
    @Transient
    private Double ewmaAlpha = 0.3;

    @JsonProperty("error_rate_percentage")
    @Column(name = "error_rate_percentage")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double errorRatePercentage;

    @JsonProperty("success_rate_percentage")
    @Column(name = "success_rate_percentage")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double successRatePercentage;

    @JsonProperty("latency_p50")
    @Column(name = "latency_p50")
    private Integer latencyP50;

    @JsonProperty("latency_p95")
    @Column(name = "latency_p95")
    private Integer latencyP95;

    @JsonProperty("latency_p99")
    @Column(name = "latency_p99")
    private Integer latencyP99;

    @JsonProperty("requests_per_minute")
    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @JsonProperty("timeout_rate_percentage")
    @Column(name = "timeout_rate_percentage")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double timeoutRatePercentage;

    @JsonProperty("uptime_percentage")
    @Column(name = "uptime_percentage")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double uptimePercentage;

    @JsonProperty("window_timestamp")
    @Column(name = "window_timestamp")
    private Long windowTimestamp;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Degradation score calculé automatiquement
    @Column(name = "degradation_score")
    private Double degradationScore;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.avgResponseTimeMs != null) {
            this.degradationScore = calculateDegradationScore();
        }
    }

    public ServerMetrics(String serverId) {
        this.serverId = serverId;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Calcule le score de dégradation basé sur les métriques
     */
    private Double calculateDegradationScore() {
        double score = 0.0;

        if (avgResponseTimeMs != null) {
            score += Math.min(500, avgResponseTimeMs);
        }

        if (errorRatePercentage != null) {
            score += errorRatePercentage * 20;
        }

        if (timeoutRatePercentage != null) {
            score += timeoutRatePercentage * 20;
        }

        if (uptimePercentage != null) {
            score += (100 - uptimePercentage) * 2;
        }

        return score;
    }

    /**
     * Calcule la latence EWMA: Lt = α * Mt + (1 - α) * Lt-1
     */
    public void calculateEwmaLatency(Double previousEwma) {
        if (avgResponseTimeMs == null) {
            this.ewmaLatencyMs = previousEwma;
            return;
        }

        if (previousEwma == null) {
            this.ewmaLatencyMs = avgResponseTimeMs;
        } else {
            this.ewmaLatencyMs = (ewmaAlpha * avgResponseTimeMs) +
                    ((1 - ewmaAlpha) * previousEwma);
        }
    }

    public boolean isHealthy() {
        return this.uptimePercentage != null && this.uptimePercentage > 90.0 &&
                this.errorRatePercentage != null && this.errorRatePercentage < 5.0 &&
                this.timeoutRatePercentage != null && this.timeoutRatePercentage < 2.0 &&
                this.successRatePercentage != null && this.successRatePercentage > 95.0;
    }

    public double getHealthScore() {
        if (uptimePercentage == null || errorRatePercentage == null ||
                timeoutRatePercentage == null || avgResponseTimeMs == null ||
                successRatePercentage == null) {
            return 0.0;
        }

        double uptimeScore = uptimePercentage / 100.0;
        double successScore = successRatePercentage / 100.0;
        double errorScore = 1.0 - (errorRatePercentage / 100.0);
        double timeoutScore = 1.0 - (timeoutRatePercentage / 100.0);

        double latencyToUse = ewmaLatencyMs != null ? ewmaLatencyMs : avgResponseTimeMs;
        double responseTimeScore = Math.max(0.0, 1.0 - (latencyToUse / 1000.0));

        return (uptimeScore * 0.25) + (successScore * 0.20) + (errorScore * 0.20) +
                (timeoutScore * 0.15) + (responseTimeScore * 0.20);
    }

    public Double getEffectiveLatency() {
        return ewmaLatencyMs != null ? ewmaLatencyMs : avgResponseTimeMs;
    }
}