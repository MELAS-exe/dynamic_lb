package com.intouch.cp.lb_aip_pidirect.repository;

import com.intouch.cp.lb_aip_pidirect.model.ServerConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServerConfigurationRepository extends JpaRepository<ServerConfiguration, Long> {

    /**
     * Find configuration by server ID
     */
    Optional<ServerConfiguration> findByServerId(String serverId);

    /**
     * Check if configuration exists for server
     */
    boolean existsByServerId(String serverId);

    /**
     * Delete configuration by server ID
     */
    void deleteByServerId(String serverId);
}