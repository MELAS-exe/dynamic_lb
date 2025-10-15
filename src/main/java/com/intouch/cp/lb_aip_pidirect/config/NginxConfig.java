package com.intouch.cp.lb_aip_pidirect.config;

import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "loadbalancer")
public class NginxConfig {

    private List<ServerInfo> servers;
    private NginxSettings nginx;
    private WeightFactors weightFactors;
    private SimulationSettings simulation;

    @Data
    public static class NginxSettings {
        private String configPath;
        private String templatePath;
        private String reloadCommand;
        private String upstreamName;
    }

    @Data
    public static class WeightFactors {
        private Double responseTime;
        private Double errorRate;
        private Double timeoutRate;
        private Double uptime;
        private Double degradation;

        // Validate that factors sum to approximately 1.0
        public boolean isValid() {
            double sum = responseTime + errorRate + timeoutRate + uptime + degradation;
            return Math.abs(sum - 1.0) < 0.01; // Allow small floating point errors
        }

        public double getSum() {
            return responseTime + errorRate + timeoutRate + uptime + degradation;
        }
    }

    @Data
    public static class SimulationSettings {
        private Boolean enabled;
        private Integer intervalSeconds;
        private Double metricsVariation;
    }

    // Helper methods
    public ServerInfo getServerById(String serverId) {
        return servers.stream()
                .filter(server -> server.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    public List<String> getServerIds() {
        return servers.stream()
                .map(ServerInfo::getId)
                .toList();
    }

    public int getServerCount() {
        return servers != null ? servers.size() : 0;
    }

    public boolean hasValidWeightFactors() {
        return weightFactors != null && weightFactors.isValid();
    }
}