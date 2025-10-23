package com.intouch.cp.lb_aip_pidirect.service;

import com.intouch.cp.lb_aip_pidirect.config.NginxConfig;
import com.intouch.cp.lb_aip_pidirect.model.ServerConfiguration;
import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import com.intouch.cp.lb_aip_pidirect.repository.ServerConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerConfigurationService {

    private final ServerConfigurationRepository configRepository;
    private final NginxConfig nginxConfig;

    /**
     * Get or create configuration for a server
     */
    public ServerConfiguration getOrCreateConfiguration(String serverId) {
        return configRepository.findByServerId(serverId)
                .orElseGet(() -> {
                    ServerConfiguration config = new ServerConfiguration(serverId);
                    return configRepository.save(config);
                });
    }

    /**
     * Get configuration for a server
     */
    public Optional<ServerConfiguration> getConfiguration(String serverId) {
        return configRepository.findByServerId(serverId);
    }

    /**
     * Get all configurations
     */
    public List<ServerConfiguration> getAllConfigurations() {
        return configRepository.findAll();
    }

    /**
     * Update configuration for a server
     */
    @Transactional
    public ServerConfiguration updateConfiguration(String serverId, ServerConfiguration updates) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);

        if (updates.getDynamicWeightEnabled() != null) {
            config.setDynamicWeightEnabled(updates.getDynamicWeightEnabled());
        }

        if (updates.getFixedWeight() != null) {
            config.setFixedWeight(updates.getFixedWeight());
        }

        if (updates.getMaxResponseTimeMs() != null) {
            config.setMaxResponseTimeMs(updates.getMaxResponseTimeMs());
        }

        if (updates.getMaxErrorRatePercentage() != null) {
            config.setMaxErrorRatePercentage(updates.getMaxErrorRatePercentage());
        }

        if (updates.getMinSuccessRatePercentage() != null) {
            config.setMinSuccessRatePercentage(updates.getMinSuccessRatePercentage());
        }

        if (updates.getMaxTimeoutRatePercentage() != null) {
            config.setMaxTimeoutRatePercentage(updates.getMaxTimeoutRatePercentage());
        }

        if (updates.getMinUptimePercentage() != null) {
            config.setMinUptimePercentage(updates.getMinUptimePercentage());
        }

        if (updates.getMaxViolationsBeforeRemoval() != null) {
            config.setMaxViolationsBeforeRemoval(updates.getMaxViolationsBeforeRemoval());
        }

        if (updates.getAutoRemovalEnabled() != null) {
            config.setAutoRemovalEnabled(updates.getAutoRemovalEnabled());
        }

        return configRepository.save(config);
    }

    /**
     * Set fixed weight for a server
     */
    @Transactional
    public ServerConfiguration setFixedWeight(String serverId, Integer weight) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setFixedWeight(weight);
        config.setDynamicWeightEnabled(false);
        return configRepository.save(config);
    }

    /**
     * Enable dynamic weight for a server
     */
    @Transactional
    public ServerConfiguration enableDynamicWeight(String serverId) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setDynamicWeightEnabled(true);
        config.setFixedWeight(null);
        return configRepository.save(config);
    }

    /**
     * Set thresholds for a server
     */
    @Transactional
    public ServerConfiguration setThresholds(String serverId,
                                            Double maxResponseTime,
                                            Double maxErrorRate,
                                            Double minSuccessRate,
                                            Double maxTimeoutRate,
                                            Double minUptime) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        
        if (maxResponseTime != null) {
            config.setMaxResponseTimeMs(maxResponseTime);
        }
        if (maxErrorRate != null) {
            config.setMaxErrorRatePercentage(maxErrorRate);
        }
        if (minSuccessRate != null) {
            config.setMinSuccessRatePercentage(minSuccessRate);
        }
        if (maxTimeoutRate != null) {
            config.setMaxTimeoutRatePercentage(maxTimeoutRate);
        }
        if (minUptime != null) {
            config.setMinUptimePercentage(minUptime);
        }

        return configRepository.save(config);
    }

    /**
     * Enable auto-removal for a server
     */
    @Transactional
    public ServerConfiguration enableAutoRemoval(String serverId, Integer maxViolations) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setAutoRemovalEnabled(true);
        if (maxViolations != null) {
            config.setMaxViolationsBeforeRemoval(maxViolations);
        }
        return configRepository.save(config);
    }

    /**
     * Disable auto-removal for a server
     */
    @Transactional
    public ServerConfiguration disableAutoRemoval(String serverId) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setAutoRemovalEnabled(false);
        config.resetViolations();
        return configRepository.save(config);
    }

    /**
     * Check if metrics violate thresholds and handle accordingly
     */
    @Transactional
    public void checkThresholds(String serverId, ServerMetrics metrics) {
        Optional<ServerConfiguration> configOpt = getConfiguration(serverId);
        
        if (configOpt.isEmpty()) {
            return;
        }

        ServerConfiguration config = configOpt.get();

        if (config.violatesThresholds(metrics)) {
            config.recordViolation();
            configRepository.save(config);

            String details = config.getViolationDetails(metrics);
            log.warn("Server {} violated thresholds (violation {}/{}): {}", 
                    serverId, 
                    config.getThresholdViolationsCount(),
                    config.getMaxViolationsBeforeRemoval(),
                    details);

            if (config.shouldBeRemoved()) {
                log.error("Server {} reached max violations ({}). Marking for removal.", 
                         serverId, config.getMaxViolationsBeforeRemoval());
                config.setManuallyRemoved(true);
                configRepository.save(config);
            }
        } else {
            // Reset violations if metrics are good
            if (config.getThresholdViolationsCount() > 0) {
                log.info("Server {} recovered. Resetting violation count.", serverId);
                config.resetViolations();
                configRepository.save(config);
            }
        }
    }

    /**
     * Manually remove a server
     */
    @Transactional
    public ServerConfiguration manuallyRemoveServer(String serverId) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setManuallyRemoved(true);
        return configRepository.save(config);
    }

    /**
     * Re-enable a server
     */
    @Transactional
    public ServerConfiguration reEnableServer(String serverId) {
        ServerConfiguration config = getOrCreateConfiguration(serverId);
        config.setManuallyRemoved(false);
        config.resetViolations();
        return configRepository.save(config);
    }

    /**
     * Reset all configurations
     */
    @Transactional
    public void resetAllConfigurations() {
        List<ServerConfiguration> configs = configRepository.findAll();
        configs.forEach(config -> {
            config.setDynamicWeightEnabled(true);
            config.setFixedWeight(null);
            config.setAutoRemovalEnabled(false);
            config.setManuallyRemoved(false);
            config.resetViolations();
        });
        configRepository.saveAll(configs);
        log.info("Reset all server configurations to defaults");
    }

    /**
     * Delete configuration for a server
     */
    @Transactional
    public void deleteConfiguration(String serverId) {
        configRepository.deleteByServerId(serverId);
        log.info("Deleted configuration for server: {}", serverId);
    }

    /**
     * Check if server is manually removed
     */
    public boolean isServerManuallyRemoved(String serverId) {
        return getConfiguration(serverId)
                .map(ServerConfiguration::getManuallyRemoved)
                .orElse(false);
    }

    /**
     * Get effective weight for a server (fixed or dynamic)
     */
    public Integer getEffectiveWeight(String serverId, Integer calculatedWeight) {
        Optional<ServerConfiguration> configOpt = getConfiguration(serverId);
        
        if (configOpt.isEmpty()) {
            return calculatedWeight;
        }

        ServerConfiguration config = configOpt.get();

        // If server is manually removed, return 0
        if (config.getManuallyRemoved()) {
            return 0;
        }

        // If fixed weight is set and dynamic is disabled, use fixed weight
        if (!config.getDynamicWeightEnabled() && config.getFixedWeight() != null) {
            return config.getFixedWeight();
        }

        // Otherwise use calculated weight
        return calculatedWeight;
    }
}