package com.intouch.cp.lb_aip_pidirect.util;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates NGINX configuration with DUAL upstream support
 * - upstream_incoming: for /incoming path (incoming servers)
 * - upstream_outgoing: for /outgoing path (outgoing servers)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NginxConfigGenerator {

    private final NginxConfig nginxConfig;
    private static final int BASE_PROXY_PORT_INCOMING = 8081;
    private static final int BASE_PROXY_PORT_OUTGOING = 9081;

    /**
     * Generate complete NGINX configuration with DUAL upstreams
     */
    public String generateDualUpstreamConfig(List<WeightAllocation> incomingWeights,
                                             List<WeightAllocation> outgoingWeights) {

        List<WeightAllocation> activeIncoming = incomingWeights.stream()
                .filter(WeightAllocation::isActive)
                .collect(Collectors.toList());

        List<WeightAllocation> activeOutgoing = outgoingWeights.stream()
                .filter(WeightAllocation::isActive)
                .collect(Collectors.toList());

        if (activeIncoming.isEmpty() && activeOutgoing.isEmpty()) {
            log.warn("No active servers in either group. Generating fallback configuration.");
            return generateFallbackConfig();
        }

        StringBuilder config = new StringBuilder();
        config.append("# ============================================\n");
        config.append("# DUAL UPSTREAM CONFIGURATION\n");
        config.append("# Generated at: ").append(LocalDateTime.now()).append("\n");
        config.append("# Incoming servers: ").append(activeIncoming.size()).append("\n");
        config.append("# Outgoing servers: ").append(activeOutgoing.size()).append("\n");
        config.append("# ============================================\n\n");

        // Generate INCOMING upstream (or placeholder if empty)
        if (activeIncoming.isEmpty()) {
            config.append("# upstream_incoming - Placeholder (no active servers)\n");
            config.append("upstream upstream_incoming {\n");
            config.append("    server 127.0.0.1:65535;  # dummy fallback\n");
            config.append("}\n\n");
        } else {
            config.append(generateUpstreamBlock("upstream_incoming", activeIncoming, BASE_PROXY_PORT_INCOMING));
            config.append("\n");
            config.append(generateProxyServers(activeIncoming, BASE_PROXY_PORT_INCOMING));
            config.append("\n");
        }

        // Generate OUTGOING upstream (or placeholder if empty)
        if (activeOutgoing.isEmpty()) {
            config.append("# upstream_outgoing - Placeholder (no active servers)\n");
            config.append("upstream upstream_outgoing {\n");
            config.append("    server 127.0.0.1:65535;  # dummy fallback\n");
            config.append("}\n");
        } else {
            config.append(generateUpstreamBlock("upstream_outgoing", activeOutgoing, BASE_PROXY_PORT_OUTGOING));
            config.append("\n");
            config.append(generateProxyServers(activeOutgoing, BASE_PROXY_PORT_OUTGOING));
        }

        log.info(generateConfigSummary(activeIncoming, activeOutgoing));

        return config.toString();
    }

    /**
     * Generate upstream block for a specific group
     */
    private String generateUpstreamBlock(String upstreamName, List<WeightAllocation> weights, int basePort) {
        StringBuilder config = new StringBuilder();

        config.append("# ").append(upstreamName).append(" - Weighted Round-Robin\n");
        config.append("upstream ").append(upstreamName).append(" {\n");

        int portNum = basePort;
        for (WeightAllocation weight : weights) {
            if (weight.isActive()) {
                config.append("    server 127.0.0.1:").append(portNum);
                config.append(" weight=").append(weight.getWeight());
                config.append(";  # ").append(weight.getServerId());
                config.append(" (").append(weight.getWeight()).append("%)");
                config.append("\n");
                portNum++;
            }
        }

        config.append("}\n");
        return config.toString();
    }

    /**
     * Generate proxy server blocks
     */
    private String generateProxyServers(List<WeightAllocation> weights, int basePort) {
        StringBuilder config = new StringBuilder();
        int portNum = basePort;

        for (WeightAllocation weight : weights) {
            if (weight.isActive()) {
                String hostname = extractHostname(weight.getServerAddress());
                String path = extractPath(weight.getServerAddress());

                config.append("# Proxy for ").append(weight.getServerId())
                        .append(" (Weight: ").append(weight.getWeight()).append("%)\n");
                config.append("server {\n");
                config.append("    listen 127.0.0.1:").append(portNum).append(";\n");
                config.append("    server_name ").append(weight.getServerId()).append(";\n");
                config.append("\n");
                config.append("    location / {\n");

                // Don't append $request_uri - it's already in the request
                // The main nginx.conf rewrites /incoming/path to /path and proxies
                // This internal proxy just forwards to the backend with its base path
                config.append("        proxy_pass https://").append(hostname).append(path).append(";\n");

                config.append("\n");
                config.append("        # Headers\n");
                config.append("        proxy_set_header Host ").append(hostname).append(";\n");
                config.append("        proxy_set_header X-Real-IP $remote_addr;\n");
                config.append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
                config.append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
                config.append("\n");
                config.append("        # Timeouts\n");
                config.append("        proxy_connect_timeout 30s;\n");
                config.append("        proxy_send_timeout 30s;\n");
                config.append("        proxy_read_timeout 30s;\n");
                config.append("\n");
                config.append("        proxy_redirect off;\n");
                config.append("        proxy_buffering on;\n");
                config.append("    }\n");
                config.append("}\n\n");

                portNum++;
            }
        }

        return config.toString();
    }

    /**
     * Extract hostname from server address
     */
    private String extractHostname(String address) {
        if (address.contains("/")) {
            return address.substring(0, address.indexOf("/"));
        }
        return address;
    }

    /**
     * Extract path from server address
     */
    private String extractPath(String address) {
        if (address.contains("/")) {
            String path = address.substring(address.indexOf("/"));
            return path.endsWith("/") ? path : path + "/";
        }
        return "/";
    }

    /**
     * Generate fallback configuration
     */
    private String generateFallbackConfig() {
        return "# No active servers - fallback configuration\n" +
                "upstream upstream_incoming {\n" +
                "    server 127.0.0.1:8090;\n" +
                "}\n\n" +
                "upstream upstream_outgoing {\n" +
                "    server 127.0.0.1:8090;\n" +
                "}\n";
    }

    /**
     * Generate configuration summary
     */
    private String generateConfigSummary(List<WeightAllocation> incoming, List<WeightAllocation> outgoing) {
        StringBuilder summary = new StringBuilder();

        summary.append("=== DUAL UPSTREAM CONFIGURATION SUMMARY ===\n");
        summary.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");

        // Incoming summary
        if (!incoming.isEmpty()) {
            int incomingTotal = incoming.stream().mapToInt(WeightAllocation::getWeight).sum();
            summary.append("INCOMING SERVERS (upstream_incoming):\n");
            summary.append("  Total weight: ").append(incomingTotal).append("\n");
            for (WeightAllocation weight : incoming) {
                double percentage = (weight.getWeight() * 100.0) / incomingTotal;
                summary.append(String.format("  - %s: %d (%.1f%%)\n",
                        weight.getServerId(), weight.getWeight(), percentage));
            }
            summary.append("\n");
        }

        // Outgoing summary
        if (!outgoing.isEmpty()) {
            int outgoingTotal = outgoing.stream().mapToInt(WeightAllocation::getWeight).sum();
            summary.append("OUTGOING SERVERS (upstream_outgoing):\n");
            summary.append("  Total weight: ").append(outgoingTotal).append("\n");
            for (WeightAllocation weight : outgoing) {
                double percentage = (weight.getWeight() * 100.0) / outgoingTotal;
                summary.append(String.format("  - %s: %d (%.1f%%)\n",
                        weight.getServerId(), weight.getWeight(), percentage));
            }
        }

        return summary.toString();
    }

    /**
     * Validate configuration
     */
    public boolean validateConfig(String config) {
        if (config == null || config.trim().isEmpty()) {
            log.error("Generated config is empty");
            return false;
        }

        int openBraces = config.length() - config.replace("{", "").length();
        int closeBraces = config.length() - config.replace("}", "").length();

        if (openBraces != closeBraces) {
            log.error("Configuration has mismatched braces. Open: {}, Close: {}", openBraces, closeBraces);
            return false;
        }

        boolean hasIncoming = config.contains("upstream upstream_incoming");
        boolean hasOutgoing = config.contains("upstream upstream_outgoing");

        if (!hasIncoming && !hasOutgoing) {
            log.error("Generated config missing both upstream directives");
            return false;
        }

        log.debug("Configuration validation passed");
        return true;
    }
}