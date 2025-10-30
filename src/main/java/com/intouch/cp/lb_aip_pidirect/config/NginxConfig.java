package com.intouch.cp.lb_aip_pidirect.config;

import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@ConfigurationProperties(prefix = "loadbalancer")
@Data
public class NginxConfig {

    private List<ServerInfo> servers = new ArrayList<>();
    private List<ServerInfo> outgoingServers = new ArrayList<>();
    private NginxSettings nginx = new NginxSettings();
    private WeightFactors weightFactors = new WeightFactors();
    private RedisConfig redis = new RedisConfig();
    private SimulationConfig simulation = new SimulationConfig();

    @Data
    public static class NginxSettings {
        private String configDir = "/nginx-config";
        private String configFile = "upstream.conf";
        private String reloadCommand = "nginx -s reload";
        private String upstreamName = "backend";
        private String incomingUpstreamName = "backend_incoming";
        private String outgoingUpstreamName = "backend_outgoing";

        public String getConfigPath() {
            return configDir + "/" + configFile;
        }
    }

    @Data
    public static class WeightFactors {
        private double responseTime = 0.25;
        private double errorRate = 0.25;
        private double timeoutRate = 0.15;
        private double uptime = 0.20;
        private double degradation = 0.15;

        public void validate() {
            double sum = responseTime + errorRate + timeoutRate + uptime + degradation;
            if (Math.abs(sum - 1.0) > 0.01) {
                throw new IllegalStateException(
                        "Weight factors must sum to 1.0, got: " + sum);
            }
        }

        /**
         * Get sum of all weight factors
         */
        public double getSum() {
            return responseTime + errorRate + timeoutRate + uptime + degradation;
        }

        /**
         * Check if weight factors are valid (sum to 1.0)
         */
        public boolean isValid() {
            double sum = getSum();
            return Math.abs(sum - 1.0) <= 0.01;
        }
    }

    @Data
    public static class RedisConfig {
        private RedisKeys keys = new RedisKeys();
        private RedisTTL ttl = new RedisTTL();
        private RedisIntervals intervals = new RedisIntervals();
    }

    @Data
    public static class RedisKeys {
        private String metricsPrefix = "metrics:";
        private String configPrefix = "config:";
        private String weightsPrefix = "weights:";
        private String nginxConfigKey = "nginx:current-config";
        private String lastUpdateKey = "nginx:last-update";
        private String instancePrefix = "instance:";
        private String lockPrefix = "lock:";
        private String incomingWeightsPrefix = "weights:incoming:";
        private String outgoingWeightsPrefix = "weights:outgoing:";
    }

    @Data
    public static class RedisTTL {
        private int metrics = 600;
        private int config = 3600;
        private int weights = 300;
        private int nginxConfig = 1800;
        private int instanceHeartbeat = 60;
    }

    @Data
    public static class RedisIntervals {
        private long configSync = 10000;
        private long metricsCleanup = 60000;
        private long heartbeat = 30000;
    }

    @Data
    public static class SimulationConfig {
        private boolean enabled = false;
        private int intervalSeconds = 60;
        private double metricsVariation = 0.2;

        /**
         * Check if simulation is enabled
         */
        public boolean getEnabled() {
            return enabled;
        }
    }

    /**
     * Get all servers (incoming servers for backward compatibility)
     */
    public List<ServerInfo> getIncomingServers() {
        return servers;
    }

    /**
     * Get outgoing servers
     */
    public List<ServerInfo> getOutgoingServers() {
        return outgoingServers;
    }

    /**
     * Check if dual upstream mode is enabled
     */
    public boolean isDualUpstreamEnabled() {
        return outgoingServers != null && !outgoingServers.isEmpty();
    }

    /**
     * Get server by ID (searches both incoming and outgoing)
     */
    public Optional<ServerInfo> getServerById(String serverId) {
        // Search in incoming servers first
        Optional<ServerInfo> incomingServer = servers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst();

        if (incomingServer.isPresent()) {
            return incomingServer;
        }

        // Search in outgoing servers
        return outgoingServers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst();
    }

    /**
     * Get total server count (incoming + outgoing)
     */
    public int getServerCount() {
        return servers.size() + outgoingServers.size();
    }

    /**
     * Get list of all server IDs (incoming + outgoing)
     */
    public List<String> getServerIds() {
        List<String> ids = new ArrayList<>();
        servers.forEach(s -> ids.add(s.getId()));
        outgoingServers.forEach(s -> ids.add(s.getId()));
        return ids;
    }

    /**
     * Check if weight factors are valid
     */
    public boolean hasValidWeightFactors() {
        return weightFactors != null && weightFactors.isValid();
    }

    /**
     * Get incoming server count
     */
    public int getIncomingServerCount() {
        return servers.size();
    }

    /**
     * Get outgoing server count
     */
    public int getOutgoingServerCount() {
        return outgoingServers.size();
    }

    /**
     * Get incoming server by ID
     */
    public Optional<ServerInfo> getIncomingServerById(String serverId) {
        return servers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst();
    }

    /**
     * Get outgoing server by ID
     */
    public Optional<ServerInfo> getOutgoingServerById(String serverId) {
        return outgoingServers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst();
    }

    /**
     * Get all incoming server IDs
     */
    public List<String> getIncomingServerIds() {
        return servers.stream()
                .map(ServerInfo::getId)
                .toList();
    }

    /**
     * Get all outgoing server IDs
     */
    public List<String> getOutgoingServerIds() {
        return outgoingServers.stream()
                .map(ServerInfo::getId)
                .toList();
    }

    /**
     * Check if a server ID exists (in either incoming or outgoing)
     */
    public boolean hasServer(String serverId) {
        return getServerById(serverId).isPresent();
    }

    /**
     * Get all enabled servers (incoming + outgoing)
     */
    public List<ServerInfo> getEnabledServers() {
        List<ServerInfo> enabled = new ArrayList<>();
        servers.stream().filter(ServerInfo::isEnabled).forEach(enabled::add);
        outgoingServers.stream().filter(ServerInfo::isEnabled).forEach(enabled::add);
        return enabled;
    }

    /**
     * Get enabled incoming servers
     */
    public List<ServerInfo> getEnabledIncomingServers() {
        return servers.stream()
                .filter(ServerInfo::isEnabled)
                .toList();
    }

    /**
     * Get enabled outgoing servers
     */
    public List<ServerInfo> getEnabledOutgoingServers() {
        return outgoingServers.stream()
                .filter(ServerInfo::isEnabled)
                .toList();
    }
}