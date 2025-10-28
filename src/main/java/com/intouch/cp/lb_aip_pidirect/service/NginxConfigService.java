package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.util.NginxConfigGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NginxConfigService {

    private final NginxConfig nginxConfig;
    private final NginxConfigGenerator configGenerator;

    /**
     * Generate NGINX configuration from weight allocations
     * Used when syncing from Redis
     */
    public String generateConfigFromWeights(List<WeightAllocation> weights) {
        return configGenerator.generateUpstreamConfig(weights);
    }

    /**
     * Apply configuration to NGINX
     */
    public boolean applyConfiguration(String config) {
        try {
            // Write configuration to file
            Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());
            Files.writeString(configPath, config);
            log.info("Wrote NGINX configuration to: {}", configPath);

            // Test configuration
            if (!testNginxConfig()) {
                log.error("NGINX configuration test failed");
                return false;
            }

            // Reload NGINX
            return reloadNginx();

        } catch (IOException e) {
            log.error("Failed to write NGINX configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Test NGINX configuration
     */
    private boolean testNginxConfig() {
        try {
            ProcessBuilder pb = new ProcessBuilder("nginx", "-t");
            Process process = pb.start();
            int exitCode = process.waitFor();

            return exitCode == 0;
        } catch (Exception e) {
            log.error("Failed to test NGINX config: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reload NGINX
     */
    private boolean reloadNginx() {
        try {
            String reloadCommand = nginxConfig.getNginx().getReloadCommand();

            ProcessBuilder pb = new ProcessBuilder(reloadCommand.split(" "));
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("NGINX reloaded successfully");
                return true;
            } else {
                log.error("NGINX reload failed with exit code: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to reload NGINX: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if NGINX is running
     */
    public boolean isNginxRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pgrep", "nginx");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("Failed to check NGINX status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get current configuration
     */
    public String getCurrentConfiguration() {
        try {
            Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());
            if (Files.exists(configPath)) {
                return Files.readString(configPath);
            }
            return null;
        } catch (IOException e) {
            log.error("Failed to read current configuration: {}", e.getMessage());
            return null;
        }
    }
}