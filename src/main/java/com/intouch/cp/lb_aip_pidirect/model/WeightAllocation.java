package com.intouch.cp.lb_aip_pidirect.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeightAllocation {

    private String serverId;
    private String serverAddress;
    private Integer weight;
    private Double healthScore;
    private String reason;
    private LocalDateTime calculatedAt;
    private ServerMetrics metrics;

    public WeightAllocation(String serverId, String serverAddress, Integer weight, Double healthScore) {
        this.serverId = serverId;
        this.serverAddress = serverAddress;
        this.weight = weight;
        this.healthScore = healthScore;
        this.calculatedAt = LocalDateTime.now();
    }

    public WeightAllocation(String serverId, String serverAddress, Integer weight, Double healthScore, String reason) {
        this(serverId, serverAddress, weight, healthScore);
        this.reason = reason;
    }

    // Helper method to check if server should receive traffic
    public boolean isActive() {
        return weight != null && weight > 0;
    }

    // Helper method to get weight as percentage of total
    public double getWeightPercentage(int totalWeight) {
        if (totalWeight == 0) return 0.0;
        return (weight.doubleValue() / totalWeight) * 100.0;
    }
}