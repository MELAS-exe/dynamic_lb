package com.intouch.cp.lb_aip_pidirect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
@ConfigurationProperties(prefix = "loadbalancer.redis")
@Data
@Slf4j
public class RedisConfig {

    private RedisKeys keys;
    private RedisTtl ttl;
    private RedisIntervals intervals;

    @Data
    public static class RedisKeys {
        private String metricsPrefix = "metrics:";
        private String configPrefix = "config:";
        private String weightsPrefix = "weights:";
        private String nginxConfigKey = "nginx:current-config";
        private String lastUpdateKey = "nginx:last-update";
        private String instancePrefix = "instance:";
        private String lockPrefix = "lock:";
    }

    @Data
    public static class RedisTtl {
        private Integer metrics = 600;          // 10 minutes
        private Integer config = 3600;          // 1 hour
        private Integer weights = 300;          // 5 minutes
        private Integer nginxConfig = 1800;     // 30 minutes
        private Integer instanceHeartbeat = 60; // 1 minute
    }

    @Data
    public static class RedisIntervals {
        private Long configSync = 10000L;       // Sync config every 10 seconds
        private Long metricsCleanup = 60000L;   // Cleanup old metrics every minute
        private Long heartbeat = 30000L;        // Heartbeat every 30 seconds
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProperties) {

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(redisProperties.getHost());
        configuration.setPort(redisProperties.getPort());

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isEmpty()) {
            configuration.setPassword(redisProperties.getPassword());
        }

        log.info("Configuring Redis connection to {}:{}",
                redisProperties.getHost(), redisProperties.getPort());

        return new LettuceConnectionFactory(configuration);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Configure ObjectMapper with JavaTimeModule for LocalDateTime serialization
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Use Jackson for value serialization
        GenericJackson2JsonRedisSerializer jackson2JsonRedisSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // Use String for key serialization
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringRedisSerializer);
        template.setHashKeySerializer(stringRedisSerializer);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);

        template.afterPropertiesSet();

        log.info("RedisTemplate configured with Jackson serialization");

        return template;
    }
}