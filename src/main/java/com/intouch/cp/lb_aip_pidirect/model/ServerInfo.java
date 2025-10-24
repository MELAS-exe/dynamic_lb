package com.intouch.cp.lb_aip_pidirect.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerInfo {

    private String id;
    private String host;
    private String port;  // Now optional - can be null or empty
    private String name;
    private boolean enabled = true;

    public ServerInfo(String id, String host, String port, String name) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.name = name;
        this.enabled = true;
    }

    /**
     * Get the server address with optional port
     * If port is null or empty, returns just the host
     * Otherwise returns host:port
     */
    public String getAddress() {
        if (port == null || port.trim().isEmpty()) {
            return host;
        }
        return host + ":" + port;
    }

    /**
     * Get the full server address (same as getAddress for backward compatibility)
     */
    public String getFullAddress() {
        return getAddress();
    }

    /**
     * Check if this server has a port specified
     */
    public boolean hasPort() {
        return port != null && !port.trim().isEmpty();
    }

    /**
     * Get the host without port
     */
    public String getHostOnly() {
        return host;
    }

    /**
     * Get the port or return default port
     */
    public String getPortOrDefault(String defaultPort) {
        if (port == null || port.trim().isEmpty()) {
            return defaultPort;
        }
        return port;
    }
}