package com.intouch.cp.lb_aip_pidirect.controller;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerInfo;
import com.intouch.cp.lb_aip_pidirect.service.MetricsCollectionService;
import com.intouch.cp.lb_aip_pidirect.service.NginxConfigService;
import com.intouch.cp.lb_aip_pidirect.service.WeightCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
@Slf4j
public class ServerManagementController {

    private final NginxConfig nginxConfig;
    private final MetricsCollectionService metricsCollectionService;
    private final WeightCalculationService weightCalculationService;
    private final NginxConfigService nginxConfigService;

    /**
     * Get all configured servers (incoming + outgoing)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllServers() {
        log.debug("Fetching all configured servers");

        Map<String, Object> response = new HashMap<>();
        response.put("incomingServers", nginxConfig.getIncomingServers());
        response.put("outgoingServers", nginxConfig.getOutgoingServers());
        response.put("totalServers", nginxConfig.getServerCount());
        response.put("incomingCount", nginxConfig.getIncomingServerCount());
        response.put("outgoingCount", nginxConfig.getOutgoingServerCount());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    /**
     * Get only incoming servers
     */
    @GetMapping("/incoming")
    public ResponseEntity<List<ServerInfo>> getIncomingServers() {
        log.debug("Fetching incoming servers");
        return ResponseEntity.ok(nginxConfig.getIncomingServers());
    }

    /**
     * Get only outgoing servers
     */
    @GetMapping("/outgoing")
    public ResponseEntity<List<ServerInfo>> getOutgoingServers() {
        log.debug("Fetching outgoing servers");
        return ResponseEntity.ok(nginxConfig.getOutgoingServers());
    }

    /**
     * Get a specific server by ID (searches both lists)
     */
    @GetMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> getServer(@PathVariable String serverId) {
        log.debug("Fetching server: {}", serverId);

        ServerInfo server = nginxConfig.getServerById(serverId);

        if (server == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("server", server);
        response.put("isIncoming", nginxConfig.hasIncomingServer(serverId));
        response.put("isOutgoing", nginxConfig.hasOutgoingServer(serverId));

        return ResponseEntity.ok(response);
    }

    /**
     * Add a new incoming server
     */
    @PostMapping("/incoming")
    public ResponseEntity<Map<String, Object>> addIncomingServer(@RequestBody ServerInfo newServer) {
        log.info("Adding new incoming server: {}", newServer.getId());

        Map<String, Object> response = new HashMap<>();

        try {
            // Validate server doesn't already exist
            if (nginxConfig.hasIncomingServer(newServer.getId())) {
                response.put("status", "error");
                response.put("message", "Incoming server with ID '" + newServer.getId() + "' already exists");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate required fields
            if (newServer.getId() == null || newServer.getHost() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields: id, host");
                return ResponseEntity.badRequest().body(response);
            }

            // Add server to incoming configuration
            nginxConfig.addIncomingServer(newServer);

            response.put("status", "success");
            response.put("message", "Incoming server added successfully");
            response.put("serverId", newServer.getId());
            response.put("serverAddress", newServer.getAddress());
            response.put("totalIncomingServers", nginxConfig.getIncomingServerCount());

            log.info("Successfully added incoming server: {} ({})", newServer.getId(), newServer.getAddress());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error adding incoming server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to add incoming server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Add a new outgoing server
     */
    @PostMapping("/outgoing")
    public ResponseEntity<Map<String, Object>> addOutgoingServer(@RequestBody ServerInfo newServer) {
        log.info("Adding new outgoing server: {}", newServer.getId());

        Map<String, Object> response = new HashMap<>();

        try {
            // Validate server doesn't already exist
            if (nginxConfig.hasOutgoingServer(newServer.getId())) {
                response.put("status", "error");
                response.put("message", "Outgoing server with ID '" + newServer.getId() + "' already exists");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate required fields
            if (newServer.getId() == null || newServer.getHost() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields: id, host");
                return ResponseEntity.badRequest().body(response);
            }

            // Add server to outgoing configuration
            nginxConfig.addOutgoingServer(newServer);

            // Trigger weight recalculation and NGINX config update for outgoing servers
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("message", "Outgoing server added successfully");
            response.put("serverId", newServer.getId());
            response.put("serverAddress", newServer.getAddress());
            response.put("totalOutgoingServers", nginxConfig.getOutgoingServerCount());

            log.info("Successfully added outgoing server: {} ({})", newServer.getId(), newServer.getAddress());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error adding outgoing server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to add outgoing server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Remove an incoming server
     */
    @DeleteMapping("/incoming/{serverId}")
    public ResponseEntity<Map<String, Object>> removeIncomingServer(@PathVariable String serverId) {
        log.info("Removing incoming server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            if (!nginxConfig.hasIncomingServer(serverId)) {
                response.put("status", "error");
                response.put("message", "Incoming server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            boolean removed = nginxConfig.removeIncomingServer(serverId);

            if (removed) {
                response.put("status", "success");
                response.put("message", "Incoming server removed successfully");
                response.put("serverId", serverId);
                response.put("remainingIncomingServers", nginxConfig.getIncomingServerCount());

                log.info("Successfully removed incoming server: {}", serverId);
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "Failed to remove incoming server");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Error removing incoming server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to remove incoming server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Remove an outgoing server
     */
    @DeleteMapping("/outgoing/{serverId}")
    public ResponseEntity<Map<String, Object>> removeOutgoingServer(@PathVariable String serverId) {
        log.info("Removing outgoing server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            if (!nginxConfig.hasOutgoingServer(serverId)) {
                response.put("status", "error");
                response.put("message", "Outgoing server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            // Don't allow removing the last outgoing server
            if (nginxConfig.getOutgoingServerCount() <= 1) {
                response.put("status", "error");
                response.put("message", "Cannot remove the last outgoing server. At least one outgoing server must remain.");
                return ResponseEntity.badRequest().body(response);
            }

            boolean removed = nginxConfig.removeOutgoingServer(serverId);

            if (removed) {
                // Trigger weight recalculation with updated server list
                var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
                var weights = weightCalculationService.calculateWeights(latestMetrics);
                nginxConfigService.updateUpstreamConfiguration(weights);

                response.put("status", "success");
                response.put("message", "Outgoing server removed successfully");
                response.put("serverId", serverId);
                response.put("remainingOutgoingServers", nginxConfig.getOutgoingServerCount());

                log.info("Successfully removed outgoing server: {}", serverId);
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "Failed to remove outgoing server");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Error removing outgoing server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to remove outgoing server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Update a server (works for both incoming and outgoing)
     */
    @PutMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable String serverId,
            @RequestBody ServerInfo updatedServer) {

        log.info("Updating server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            // Find the server (could be in either list)
            ServerInfo existingServer = nginxConfig.getServerById(serverId);

            if (existingServer == null) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            // Update server properties
            if (updatedServer.getHost() != null) {
                existingServer.setHost(updatedServer.getHost());
            }

            if (updatedServer.getName() != null) {
                existingServer.setName(updatedServer.getName());
            }

            if (updatedServer.hasPort()) {
                existingServer.setPort(updatedServer.getPort());
            }

            existingServer.setEnabled(updatedServer.isEnabled());

            // If it's an outgoing server, trigger weight recalculation
            if (nginxConfig.hasOutgoingServer(serverId)) {
                var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
                var weights = weightCalculationService.calculateWeights(latestMetrics);
                nginxConfigService.updateUpstreamConfiguration(weights);
            }

            response.put("status", "success");
            response.put("message", "Server updated successfully");
            response.put("serverId", serverId);
            response.put("server", existingServer);
            response.put("isIncoming", nginxConfig.hasIncomingServer(serverId));
            response.put("isOutgoing", nginxConfig.hasOutgoingServer(serverId));

            log.info("Successfully updated server: {} ({})", serverId, existingServer.getAddress());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error updating server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to update server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Enable/Disable a server (works for both incoming and outgoing)
     */
    @PostMapping("/{serverId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleServer(@PathVariable String serverId) {
        log.info("Toggling server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            ServerInfo server = nginxConfig.getServerById(serverId);

            if (server == null) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            // Toggle enabled status
            server.setEnabled(!server.isEnabled());

            // If it's an outgoing server, trigger weight recalculation
            if (nginxConfig.hasOutgoingServer(serverId)) {
                var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
                var weights = weightCalculationService.calculateWeights(latestMetrics);
                nginxConfigService.updateUpstreamConfiguration(weights);
            }

            response.put("status", "success");
            response.put("message", "Server " + (server.isEnabled() ? "enabled" : "disabled"));
            response.put("serverId", serverId);
            response.put("enabled", server.isEnabled());
            response.put("isIncoming", nginxConfig.hasIncomingServer(serverId));
            response.put("isOutgoing", nginxConfig.hasOutgoingServer(serverId));

            log.info("Server {} is now {}", serverId, server.isEnabled() ? "enabled" : "disabled");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error toggling server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to toggle server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}