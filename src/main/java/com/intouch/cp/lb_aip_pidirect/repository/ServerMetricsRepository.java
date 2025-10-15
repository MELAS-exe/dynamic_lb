package com.intouch.cp.lb_aip_pidirect.repository;

import com.intouch.cp.lb_aip_pidirect.model.ServerMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServerMetricsRepository extends JpaRepository<ServerMetrics, Long> {

    // Find the latest metrics for a specific server
    Optional<ServerMetrics> findFirstByServerIdOrderByCreatedAtDesc(String serverId);

    // Find all latest metrics for all servers
    @Query("SELECT sm FROM ServerMetrics sm WHERE sm.createdAt = " +
            "(SELECT MAX(sm2.createdAt) FROM ServerMetrics sm2 WHERE sm2.serverId = sm.serverId)")
    List<ServerMetrics> findLatestMetricsForAllServers();

    // Find metrics for a server within a time range
    List<ServerMetrics> findByServerIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String serverId, LocalDateTime startTime, LocalDateTime endTime);

    // Find all metrics for a server
    List<ServerMetrics> findByServerIdOrderByCreatedAtDesc(String serverId);

    // Find metrics older than a certain date (for cleanup)
    List<ServerMetrics> findByCreatedAtBefore(LocalDateTime cutoffTime);

    // Custom query to get average response time over last N minutes
    @Query("SELECT AVG(sm.avgResponseTimeMs) FROM ServerMetrics sm " +
            "WHERE sm.serverId = :serverId AND sm.createdAt >= :since")
    Double getAverageResponseTimeForServer(@Param("serverId") String serverId,
                                           @Param("since") LocalDateTime since);

    // Custom query to get error rate trend
    @Query("SELECT AVG(sm.errorRatePercentage) FROM ServerMetrics sm " +
            "WHERE sm.serverId = :serverId AND sm.createdAt >= :since")
    Double getAverageErrorRateForServer(@Param("serverId") String serverId,
                                        @Param("since") LocalDateTime since);

    // Count metrics for a server
    Long countByServerId(String serverId);

    // Delete old metrics (cleanup)
    void deleteByCreatedAtBefore(LocalDateTime cutoffTime);
}