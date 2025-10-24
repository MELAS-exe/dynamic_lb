package com.intouch.cp.lb_aip_pidirect.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to monitor and log traffic distribution statistics
 * Displays distribution directly in docker logs
 */
@Service
@Slf4j
public class TrafficStatsService {

    private static final String ACCESS_LOG = "/var/log/nginx/access.log";
    private static final int SAMPLE_SIZE = 100;

    /**
     * Log traffic distribution stats every 30 seconds
     * Shows distribution with visual progress bars
     */
    @Scheduled(fixedRate = 30000)
    public void logTrafficDistribution() {
        try {
            Map<String, Integer> distribution = getTrafficDistribution();
            
            if (distribution.isEmpty()) {
                log.debug("No traffic data available yet");
                return;
            }
            
            int total = distribution.values().stream().mapToInt(Integer::intValue).sum();
            
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔄 TRAFFIC DISTRIBUTION (Last {} requests)", SAMPLE_SIZE);
            log.info("═══════════════════════════════════════════════════════════");
            
            distribution.entrySet().stream()
                    .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                    .forEach(entry -> {
                        String proxy = entry.getKey();
                        int count = entry.getValue();
                        double percentage = (count * 100.0) / total;
                        String bar = generateBar(percentage);
                        
                        log.info(String.format("  %-20s %3d requests (%5.1f%%) %s", 
                                proxy, count, percentage, bar));
                    });
            
            log.info("  Total: {} requests", total);
            
            // Check if distribution is balanced
            if (distribution.size() == 2) {
                double maxPercentage = distribution.values().stream()
                        .mapToDouble(count -> (count * 100.0) / total)
                        .max()
                        .orElse(0);
                
                if (maxPercentage > 75) {
                    log.warn("⚠️  Traffic is heavily skewed to one backend ({}%)", 
                            String.format("%.1f", maxPercentage));
                } else if (Math.abs(maxPercentage - 50) < 5) {
                    log.info("✅ Traffic is well balanced (~50/50)");
                }
            }
            
            log.info("═══════════════════════════════════════════════════════════");
            
        } catch (Exception e) {
            log.debug("Could not read traffic stats: {}", e.getMessage());
        }
    }
    
    /**
     * Get traffic distribution from NGINX access logs
     */
    private Map<String, Integer> getTrafficDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        
        try {
            // Read last N lines from access log and count by upstream proxy
            ProcessBuilder pb = new ProcessBuilder(
                "sh", "-c", 
                String.format("tail -%d %s | grep 'upstream:' | awk '{print $11}' | sort | uniq -c",
                        SAMPLE_SIZE, ACCESS_LOG)
            );
            
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        int count = Integer.parseInt(parts[0]);
                        String proxy = parts[1];
                        distribution.put(proxy, count);
                    }
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("Process exited with code: {}", exitCode);
            }
            
        } catch (Exception e) {
            log.debug("Error reading traffic distribution: {}", e.getMessage());
        }
        
        return distribution;
    }
    
    /**
     * Generate visual progress bar
     */
    private String generateBar(double percentage) {
        int barLength = (int) (percentage / 2); // Max 50 chars for 100%
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < barLength; i++) {
            bar.append("█");
        }
        return bar.toString();
    }
    
    /**
     * Log detailed distribution summary every 5 minutes
     * Includes backend hostname information
     */
    @Scheduled(fixedRate = 300000)
    public void logDistributionSummary() {
        try {
            Map<String, Integer> proxyDistribution = getTrafficDistribution();
            Map<String, Integer> backendDistribution = getBackendDistribution();
            
            if (proxyDistribution.isEmpty()) {
                return;
            }
            
            int total = proxyDistribution.values().stream().mapToInt(Integer::intValue).sum();
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("📊 5-MINUTE TRAFFIC SUMMARY");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("Total Requests: {}", total);
            log.info("");
            log.info("Proxy Distribution:");
            proxyDistribution.forEach((proxy, count) -> {
                double percentage = (count * 100.0) / total;
                log.info("  {} → {} requests ({:.1f}%)", 
                        proxy, count, percentage);
            });
            
            if (!backendDistribution.isEmpty()) {
                log.info("");
                log.info("Backend Distribution:");
                backendDistribution.forEach((backend, count) -> {
                    double percentage = (count * 100.0) / total;
                    log.info("  {} → {} requests ({:.1f}%)", 
                            backend, count, percentage);
                });
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
        } catch (Exception e) {
            log.debug("Could not generate summary: {}", e.getMessage());
        }
    }
    
    /**
     * Get backend hostname distribution from logs
     */
    private Map<String, Integer> getBackendDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "sh", "-c", 
                String.format("tail -%d %s | awk '/backend:/{print $13}' | sort | uniq -c",
                        SAMPLE_SIZE, ACCESS_LOG)
            );
            
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length >= 2) {
                        int count = Integer.parseInt(parts[0]);
                        String backend = parts[1];
                        distribution.put(backend, count);
                    }
                }
            }
            
            process.waitFor();
            
        } catch (Exception e) {
            log.debug("Error reading backend distribution: {}", e.getMessage());
        }
        
        return distribution;
    }
    
    /**
     * Check if running in Docker environment
     */
    private boolean isRunningInDocker() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "[ -f /.dockerenv ] && echo true || echo false");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            return "true".equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}