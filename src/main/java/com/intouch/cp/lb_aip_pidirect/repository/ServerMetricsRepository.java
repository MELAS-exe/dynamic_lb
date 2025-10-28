package com.intouch.cp.lb_aip_pidirect.repository;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ServerMetrics entity
 * Provides data access methods for server performance metrics
 */
@Repository
public interface ServerMetricsRepository extends JpaRepository<ServerMetrics, Long> {

    /**
     * Find all metrics for a specific server, ordered by creation date (newest first)
     */
    List<ServerMetrics> findByServerIdOrderByCreatedAtDesc(String serverId);

    /**
     * Find metrics for a server within a specific time range
     */
    List<ServerMetrics> findByServerIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String serverId, LocalDateTime start, LocalDateTime end);

    /**
     * Find the most recent metrics for a specific server
     */
    Optional<ServerMetrics> findFirstByServerIdOrderByCreatedAtDesc(String serverId);

    /**
     * Delete all metrics created before a specific date (for cleanup)
     */
    int deleteByCreatedAtBefore(LocalDateTime cutoff);

    /**
     * Get the latest metrics for all servers
     * Uses a subquery to get only the most recent metric for each server
     */
    @Query("SELECT m FROM ServerMetrics m WHERE m.id IN " +
            "(SELECT MAX(m2.id) FROM ServerMetrics m2 GROUP BY m2.serverId)")
    List<ServerMetrics> findLatestMetricsForAllServers();

    /**
     * Alternative implementation using native query if needed
     */
    @Query(value = "SELECT * FROM server_metrics m1 " +
            "WHERE m1.created_at = (SELECT MAX(m2.created_at) " +
            "FROM server_metrics m2 WHERE m2.server_id = m1.server_id)",
            nativeQuery = true)
    List<ServerMetrics> findLatestMetricsForAllServersNative();

    /**
     * Count metrics for a specific server
     */
    long countByServerId(String serverId);

    /**
     * Find metrics created after a specific date
     */
    List<ServerMetrics> findByCreatedAtAfter(LocalDateTime after);

    /**
     * Find all metrics for multiple servers
     */
    List<ServerMetrics> findByServerIdIn(List<String> serverIds);
}