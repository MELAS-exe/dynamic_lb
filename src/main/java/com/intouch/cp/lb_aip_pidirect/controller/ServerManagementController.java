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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Get all configured servers
     */
    @GetMapping
    public ResponseEntity<List<ServerInfo>> getAllServers() {
        log.debug("Fetching all configured servers");
        return ResponseEntity.ok(nginxConfig.getServers());
    }

    /**
     * Get a specific server by ID
     */
    @GetMapping("/{serverId}")
    public ResponseEntity<ServerInfo> getServer(@PathVariable String serverId) {
        log.debug("Fetching server: {}", serverId);

        Optional<ServerInfo> server = nginxConfig.getServerById(serverId);
        if (server.isPresent()) {
            return ResponseEntity.ok(server.get());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Add a new server dynamically
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody ServerInfo newServer) {
        log.info("Adding new server: {}", newServer.getId());

        Map<String, Object> response = new HashMap<>();

        try {
            // Validate server doesn't already exist
            if (nginxConfig.getServerById(newServer.getId()).isPresent()) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + newServer.getId() + "' already exists");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate required fields
            if (newServer.getId() == null || newServer.getHost() == null) {
                response.put("status", "error");
                response.put("message", "Missing required fields: id, host, port");
                return ResponseEntity.badRequest().body(response);
            }

            // Add server to configuration
            nginxConfig.getServers().add(newServer);

            // Trigger weight recalculation with new server list
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("message", "Server added successfully");
            response.put("serverId", newServer.getId());
            response.put("serverAddress", newServer.getAddress());
            response.put("totalServers", nginxConfig.getServerCount());

            log.info("Successfully added server: {} ({})", newServer.getId(), newServer.getAddress());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error adding server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to add server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Remove a server dynamically
     */
    @DeleteMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String serverId) {
        log.info("Removing server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            // Find the server
            Optional<ServerInfo> serverToRemoveOpt = nginxConfig.getServerById(serverId);

            if (!serverToRemoveOpt.isPresent()) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            ServerInfo serverToRemove = serverToRemoveOpt.get();

            // Don't allow removing the last server
            if (nginxConfig.getServerCount() <= 1) {
                response.put("status", "error");
                response.put("message", "Cannot remove the last server. At least one server must remain.");
                return ResponseEntity.badRequest().body(response);
            }

            // Remove server from configuration
            nginxConfig.getServers().remove(serverToRemove);

            // Trigger weight recalculation without the removed server
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("message", "Server removed successfully");
            response.put("serverId", serverId);
            response.put("remainingServers", nginxConfig.getServerCount());

            log.info("Successfully removed server: {}", serverId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error removing server: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "Failed to remove server: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Update a server's configuration
     */
    @PutMapping("/{serverId}")
    public ResponseEntity<Map<String, Object>> updateServer(
            @PathVariable String serverId,
            @RequestBody ServerInfo updatedServer) {

        log.info("Updating server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            // Find the server
            Optional<ServerInfo> existingServerOpt = nginxConfig.getServerById(serverId);

            if (!existingServerOpt.isPresent()) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            ServerInfo existingServer = existingServerOpt.get();

            // Update server properties
            if (updatedServer.getHost() != null) {
                existingServer.setHost(updatedServer.getHost());
            }

            if (updatedServer.getName() != null) {
                existingServer.setName(updatedServer.getName());
            }

            if(updatedServer.hasPort()){
                existingServer.setPort(updatedServer.getPort());
            }
            existingServer.setEnabled(updatedServer.isEnabled());

            // Trigger weight recalculation with updated server
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("message", "Server updated successfully");
            response.put("serverId", serverId);
            response.put("server", existingServer);

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
     * Enable/Disable a server
     */
    @PostMapping("/{serverId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleServer(@PathVariable String serverId) {
        log.info("Toggling server: {}", serverId);

        Map<String, Object> response = new HashMap<>();

        try {
            Optional<ServerInfo> serverOpt = nginxConfig.getServerById(serverId);

            if (!serverOpt.isPresent()) {
                response.put("status", "error");
                response.put("message", "Server with ID '" + serverId + "' not found");
                return ResponseEntity.notFound().build();
            }

            ServerInfo server = serverOpt.get();

            // Toggle enabled status
            server.setEnabled(!server.isEnabled());

            // Trigger weight recalculation
            var latestMetrics = metricsCollectionService.getLatestMetricsForAllServers();
            var weights = weightCalculationService.calculateWeights(latestMetrics);
            nginxConfigService.updateUpstreamConfiguration(weights);

            response.put("status", "success");
            response.put("message", "Server " + (server.isEnabled() ? "enabled" : "disabled"));
            response.put("serverId", serverId);
            response.put("enabled", server.isEnabled());

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