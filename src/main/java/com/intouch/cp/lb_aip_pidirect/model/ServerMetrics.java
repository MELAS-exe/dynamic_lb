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

    @JsonProperty("avg_response_time_ms")
    @Column(name = "avg_response_time_ms")
    private Double avgResponseTimeMs;

    @JsonProperty("degradation_score")
    @Column(name = "degradation_score")
    private Double degradationScore;

    @JsonProperty("error_rate_percentage")
    @Column(name = "error_rate_percentage")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Double errorRatePercentage;

    @JsonProperty("latency_p50")
    @Column(name = "latency_p50")
    private Integer latencyP50;

    @JsonProperty("latency_p95")
    @Column(name = "latency_p95")
    private Integer latencyP95;

    @JsonProperty("latency_p99")
    @Column(name = "latency_p99")
    private Integer latencyP99;

    @JsonProperty("request_per_minute")
    @Column(name = "request_per_minute")
    private Integer requestPerMinute;

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

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // Custom constructor for easy creation
    public ServerMetrics(String serverId) {
        this.serverId = serverId;
        this.createdAt = LocalDateTime.now();
    }

    // Helper method to check if metrics are healthy
    public boolean isHealthy() {
        return this.uptimePercentage != null && this.uptimePercentage > 90.0 &&
                this.errorRatePercentage != null && this.errorRatePercentage < 5.0 &&
                this.timeoutRatePercentage != null && this.timeoutRatePercentage < 2.0;
    }

    // Helper method to get overall health score (0-1)
    public double getHealthScore() {
        if (uptimePercentage == null || errorRatePercentage == null ||
                timeoutRatePercentage == null || avgResponseTimeMs == null) {
            return 0.0;
        }

        double uptimeScore = uptimePercentage / 100.0;
        double errorScore = 1.0 - (errorRatePercentage / 100.0);
        double timeoutScore = 1.0 - (timeoutRatePercentage / 100.0);
        double responseTimeScore = Math.max(0.0, 1.0 - (avgResponseTimeMs / 1000.0));

        return (uptimeScore + errorScore + timeoutScore + responseTimeScore) / 4.0;
    }
}