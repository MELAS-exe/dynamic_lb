package com.intouch.cp.lb_aip_pidirect.config;

import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@Component
@ConfigurationProperties(prefix = "loadbalancer")
public class NginxConfig {

    private List<ServerInfo> incomingServers = new ArrayList<>();
    private List<ServerInfo> outgoingServers = new ArrayList<>();
    private NginxSettings nginx;
    private WeightFactors weightFactors;
    private SimulationSettings simulation;
    private RedisSettings redis;


    @Data
    public static class RedisSettings {
        private RedisKeys keys;
        private RedisTtl ttl;
        private RedisIntervals intervals;
    }

    @Data
    public static class RedisKeys {
        private String metricsPrefix;
        private String configPrefix;
        private String weightsPrefix;
        private String nginxConfigKey;
        private String lastUpdateKey;
    }

    @Data
    public static class RedisTtl {
        private Integer metrics;
        private Integer config;
        private Integer weights;
        private Integer nginxConfig;
    }

    @Data
    public static class RedisIntervals {
        private Long configSync;
        private Long metricsCleanup;
    }

    @Data
    public static class NginxSettings {
        private String configDir;
        private String configFile;
        private String reloadCommand;
        private String upstreamName;

        // Computed property that combines configDir and configFile
        public String getConfigPath() {
            if (configDir == null || configFile == null) {
                return null;
            }
            // Ensure proper path separator
            if (configDir.endsWith("/")) {
                return configDir + configFile;
            }
            return configDir + "/" + configFile;
        }
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

    @Data
    public static class ServerConfig {
        private String id;
        private String host;
        private Integer port;
        private String name;
    }

    // ========== HELPER METHODS FOR DUAL CONFIGURATION ==========

    /**
     * Get all servers (combined incoming and outgoing)
     * This is the main method that controllers should use
     */
    public List<ServerInfo> getServers() {
        return Stream.concat(
                incomingServers != null ? incomingServers.stream() : Stream.empty(),
                outgoingServers != null ? outgoingServers.stream() : Stream.empty()
        ).collect(Collectors.toList());
    }

    /**
     * Get only incoming servers (servers that send metrics TO this load balancer)
     */
    public List<ServerInfo> getIncomingServers() {
        return incomingServers != null ? incomingServers : new ArrayList<>();
    }

    /**
     * Get only outgoing servers (servers that this load balancer sends traffic TO)
     */
    public List<ServerInfo> getOutgoingServers() {
        return outgoingServers != null ? outgoingServers : new ArrayList<>();
    }

    /**
     * Get server by ID from all servers (incoming + outgoing)
     */
    public ServerInfo getServerById(String serverId) {
        return getServers().stream()
                .filter(server -> server.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get incoming server by ID
     */
    public ServerInfo getIncomingServerById(String serverId) {
        return getIncomingServers().stream()
                .filter(server -> server.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get outgoing server by ID
     */
    public ServerInfo getOutgoingServerById(String serverId) {
        return getOutgoingServers().stream()
                .filter(server -> server.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all server IDs (combined)
     */
    public List<String> getServerIds() {
        return getServers().stream()
                .map(ServerInfo::getId)
                .toList();
    }

    /**
     * Get incoming server IDs
     */
    public List<String> getIncomingServerIds() {
        return getIncomingServers().stream()
                .map(ServerInfo::getId)
                .toList();
    }

    /**
     * Get outgoing server IDs
     */
    public List<String> getOutgoingServerIds() {
        return getOutgoingServers().stream()
                .map(ServerInfo::getId)
                .toList();
    }

    /**
     * Get total server count (incoming + outgoing)
     */
    public int getServerCount() {
        return getServers().size();
    }

    /**
     * Get incoming server count
     */
    public int getIncomingServerCount() {
        return getIncomingServers().size();
    }

    /**
     * Get outgoing server count
     */
    public int getOutgoingServerCount() {
        return getOutgoingServers().size();
    }

    /**
     * Check if a server ID exists in any list
     */
    public boolean hasServer(String serverId) {
        return getServerById(serverId) != null;
    }

    /**
     * Check if a server ID exists in incoming list
     */
    public boolean hasIncomingServer(String serverId) {
        return getIncomingServerById(serverId) != null;
    }

    /**
     * Check if a server ID exists in outgoing list
     */
    public boolean hasOutgoingServer(String serverId) {
        return getOutgoingServerById(serverId) != null;
    }

    /**
     * Add a server to incoming list
     */
    public void addIncomingServer(ServerInfo server) {
        if (incomingServers == null) {
            incomingServers = new ArrayList<>();
        }
        incomingServers.add(server);
    }

    /**
     * Add a server to outgoing list
     */
    public void addOutgoingServer(ServerInfo server) {
        if (outgoingServers == null) {
            outgoingServers = new ArrayList<>();
        }
        outgoingServers.add(server);
    }

    /**
     * Remove a server from incoming list
     */
    public boolean removeIncomingServer(String serverId) {
        if (incomingServers == null) {
            return false;
        }
        return incomingServers.removeIf(s -> s.getId().equals(serverId));
    }

    /**
     * Remove a server from outgoing list
     */
    public boolean removeOutgoingServer(String serverId) {
        if (outgoingServers == null) {
            return false;
        }
        return outgoingServers.removeIf(s -> s.getId().equals(serverId));
    }

    /**
     * Remove a server from any list it appears in
     */
    public boolean removeServer(String serverId) {
        boolean removedFromIncoming = removeIncomingServer(serverId);
        boolean removedFromOutgoing = removeOutgoingServer(serverId);
        return removedFromIncoming || removedFromOutgoing;
    }

    /**
     * Check if dual upstream mode is enabled
     * (Both incoming and outgoing servers are configured)
     */
    public boolean isDualUpstreamEnabled() {
        return incomingServers != null && !incomingServers.isEmpty() &&
                outgoingServers != null && !outgoingServers.isEmpty();
    }

    public boolean hasValidWeightFactors() {
        return weightFactors != null && weightFactors.isValid();
    }
}