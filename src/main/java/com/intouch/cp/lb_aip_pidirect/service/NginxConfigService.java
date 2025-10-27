package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.WeightAllocation;
import com.intouch.cp.lb_aip_pidirect.util.NginxConfigGenerator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class NginxConfigService {

    private final NginxConfig nginxConfig;
    private final NginxConfigGenerator configGenerator;

    public NginxConfigService(NginxConfig nginxConfig, NginxConfigGenerator configGenerator) {
        this.nginxConfig = nginxConfig;
        this.configGenerator = configGenerator;
    }

    @PostConstruct
    public void init() {
        log.info("=== Initializing NGINX Configuration Service ===");

        if (isRunningInDocker()) {
            log.info("Running in Docker mode - NGINX managed by separate container");
            log.info("Configuration will be written to shared volume: {}",
                    nginxConfig.getNginx().getConfigPath());
        } else {
            log.info("Running in standalone mode");
            // Vérifications pour mode local uniquement
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    boolean isRunning = isNginxRunning();
                    log.info("NGINX running status: {}", isRunning);

                    if (!isRunning) {
                        log.warn("NGINX is not running. Please start it manually.");
                    } else {
                        log.info("✓ NGINX is already running");
                    }
                } catch (Exception e) {
                    log.error("Error during NGINX initialization: {}", e.getMessage(), e);
                }
            }, "nginx-startup-thread").start();
        }

        log.info("=== NGINX Configuration Service Initialized ===");
    }

    public void updateUpstreamConfiguration(List<WeightAllocation> weights) {
        try {
            log.info("Updating NGINX upstream configuration with {} servers", weights.size());

            // Generate new configuration content
            String configContent = configGenerator.generateUpstreamConfig(weights);

            // Validate configuration before writing - USE THE GENERATOR'S VALIDATION METHOD
            if (!configGenerator.validateGeneratedConfig(configContent)) {
                log.error("Generated configuration is invalid. Aborting update.");
                log.error("Failed config content preview (first 500 chars): {}",
                        configContent != null ? configContent.substring(0, Math.min(500, configContent.length())) : "null");
                return;
            }

            // Backup current configuration
            backupCurrentConfiguration();

            // Write new configuration
            writeConfigurationFile(configContent);

            log.info("Successfully updated NGINX configuration");
            logWeightChanges(weights);

            // En mode Docker, le script auto-reload.sh détectera le changement
            // En mode local, reload NGINX
            if (!isRunningInDocker()) {
                if (testNginxConfiguration()) {
                    if (reloadNginx()) {
                        log.info("NGINX reloaded successfully");
                    } else {
                        log.error("Failed to reload NGINX");
                        rollbackConfiguration();
                    }
                } else {
                    log.error("NGINX configuration test failed. Rolling back.");
                    rollbackConfiguration();
                }
            } else {
                log.info("Configuration written. NGINX will auto-reload within 5 seconds.");
            }

        } catch (Exception e) {
            log.error("Error updating NGINX configuration: {}", e.getMessage(), e);
        }
    }

    private void writeLocationBlocks(String content) throws IOException {
        String configDir = nginxConfig.getNginx().getConfigPath();
        Path locationPath = Paths.get(configDir).getParent().resolve("locations.conf");

        // Ensure directory exists
        Files.createDirectories(locationPath.getParent());

        // Write configuration atomically
        Path tempPath = Paths.get(locationPath.toString() + ".tmp");
        Files.writeString(tempPath, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        // Atomic move
        Files.move(tempPath, locationPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        log.info("Location blocks written to: {}", locationPath);
    }

    private void writeConfigurationFile(String content) throws IOException {
        Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());

        // Ensure directory exists
        Files.createDirectories(configPath.getParent());

        // Write configuration atomically
        Path tempPath = Paths.get(configPath.toString() + ".tmp");
        Files.writeString(tempPath, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        // Atomic move
        Files.move(tempPath, configPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        log.info("Configuration written to: {}", configPath);
    }

    private boolean testNginxConfiguration() {
        if (isRunningInDocker()) {
            // En Docker, on fait confiance à la validation du script auto-reload
            log.info("Docker mode: Configuration test will be performed by NGINX container");
            return true;
        }

        try {
            String reloadCmd = nginxConfig.getNginx().getReloadCommand();
            String nginxPath = reloadCmd.split(" ")[0];
            Path nginxExePath = Paths.get(nginxPath);
            log.info("The problematic line --------> nginxExePath: " + nginxExePath.toString());

            Path nginxExecutableDir = nginxExePath.getParent();
            log.info("the problematic line --------> nginxExecutableDir: " +
                    (nginxExecutableDir != null ? nginxExecutableDir.toString() : "null"));

            ProcessBuilder pb = new ProcessBuilder(nginxPath, "-t");

            // Only set directory if parent exists
            if (nginxExecutableDir != null) {
                pb.directory(nginxExecutableDir.toFile());
            }

            pb.redirectErrorStream(true);

            Process process = pb.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.debug("NGINX configuration test passed");
                return true;
            } else {
                log.error("NGINX configuration test failed with exit code: {}", exitCode);
                log.error("NGINX output: {}", output.toString());
                return false;
            }
        } catch (Exception e) {
            log.error("Error testing NGINX configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean reloadNginx() {
        if (isRunningInDocker()) {
            // En Docker, le reload est géré par le script auto-reload.sh
            log.info("Docker mode: NGINX reload handled by auto-reload script");
            return true;
        }

        try {
            String[] command = nginxConfig.getNginx().getReloadCommand().split(" ");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("NGINX reloaded successfully");
                return true;
            } else {
                log.error("NGINX reload failed with exit code: {}", exitCode);
                log.error("Output: {}", output.toString());
                return false;
            }
        } catch (Exception e) {
            log.error("Error reloading NGINX: {}", e.getMessage(), e);
            return false;
        }
    }

    private void backupCurrentConfiguration() {
        try {
            Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());
            if (Files.exists(configPath)) {
                Path backupPath = Paths.get(configPath + ".backup." + System.currentTimeMillis());
                Files.copy(configPath, backupPath);
                log.debug("Configuration backed up to: {}", backupPath);
            }
        } catch (Exception e) {
            log.warn("Failed to backup configuration: {}", e.getMessage());
        }
    }

    private void rollbackConfiguration() {
        try {
            Path configDir = Paths.get(nginxConfig.getNginx().getConfigPath()).getParent();
            String configFileName = Paths.get(nginxConfig.getNginx().getConfigPath()).getFileName().toString();

            Path latestBackup = Files.list(configDir)
                    .filter(path -> path.getFileName().toString().startsWith(configFileName + ".backup."))
                    .max((p1, p2) -> p1.getFileName().toString().compareTo(p2.getFileName().toString()))
                    .orElse(null);

            if (latestBackup != null) {
                Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());
                Files.copy(latestBackup, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                if (!isRunningInDocker()) {
                    reloadNginx();
                }

                log.info("Configuration rolled back from: {}", latestBackup);
            } else {
                log.error("No backup found for rollback");
            }
        } catch (Exception e) {
            log.error("Error during configuration rollback: {}", e.getMessage());
        }
    }

    private void logWeightChanges(List<WeightAllocation> weights) {
        log.info("=== Weight Distribution ===");
        int totalWeight = weights.stream()
                .filter(WeightAllocation::isActive)
                .mapToInt(WeightAllocation::getWeight)
                .sum();

        for (WeightAllocation weight : weights) {
            if (weight.isActive()) {
                double percentage = weight.getWeightPercentage(totalWeight);
                log.info("Server: {} | Weight: {} ({:.1f}%) | Health: {:.2f} | Reason: {}",
                        weight.getServerId(),
                        weight.getWeight(),
                        percentage,
                        weight.getHealthScore(),
                        weight.getReason());
            } else {
                log.info("Server: {} | DISABLED | Health: {:.2f} | Reason: {}",
                        weight.getServerId(),
                        weight.getHealthScore(),
                        weight.getReason());
            }
        }
        log.info("=== Total Active Weight: {} ===", totalWeight);
    }

    public String getCurrentConfiguration() {
        try {
            Path configPath = Paths.get(nginxConfig.getNginx().getConfigPath());
            if (Files.exists(configPath)) {
                return Files.readString(configPath);
            }
        } catch (Exception e) {
            log.error("Error reading current configuration: {}", e.getMessage());
        }
        return null;
    }

    private boolean isRunningInDocker() {
        try {
            return Files.exists(Paths.get("/.dockerenv")) ||
                    Files.exists(Paths.get("/proc/1/cgroup")) &&
                            Files.readString(Paths.get("/proc/1/cgroup")).contains("docker");
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isNginxRunning() {
        if (isRunningInDocker()) {
            // En Docker, on suppose que NGINX est géré par son propre container
            return true;
        }

        try {
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                ProcessBuilder pb = new ProcessBuilder("tasklist");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("nginx.exe")) {
                        return true;
                    }
                }
                return false;
            } else {
                ProcessBuilder pb = new ProcessBuilder("pgrep", "nginx");
                Process process = pb.start();
                return process.waitFor() == 0;
            }
        } catch (Exception e) {
            log.error("Error checking if NGINX is running: {}", e.getMessage());
            return false;
        }
    }
}