package io.showcase.order.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * SPRING CACHE ABSTRACTION backed by REDIS.
 *
 * <p>{@code @Cacheable/@CachePut/@CacheEvict} are provider-neutral. The {@link CacheManager} decides
 * where entries live. Redis (rather than an in-memory Caffeine cache) means all instances share ONE
 * cache: an eviction is seen cluster-wide, and a restart doesn't cold-start the cache.
 *
 * <p>Decisions encoded below:
 * <ul>
 *   <li><b>Per-cache TTLs</b>: prices may be 30 s stale; daily reports can be 10 minutes stale.
 *       ALWAYS set TTLs. An unbounded cache is a memory leak with extra steps.</li>
 *   <li><b>No caching of null</b>: a "not found" shouldn't stick for the whole TTL.</li>
 *   <li><b>Key prefix with a version</b> ("v1::"): changing the cached CLASS shape would break
 *       deserialization of old entries; bumping the prefix sidesteps that during deploys.</li>
 *   <li><b>Serialization</b>: the default JDK serialization requires {@code Serializable} values (our
 *       records are). A JSON serializer is more language-neutral and tolerant of class changes; it's
 *       worth switching to once other services read the same cache.</li>
 *   <li><b>Transaction awareness</b>: {@code transactionAware()} delays puts and evictions until the
 *       surrounding DB transaction commits, so the cache never holds data from a transaction that
 *       rolled back.</li>
 * </ul>
 * Caching patterns (cache-aside, write-through, stampede protection) are covered in
 * docs/11-caching-redis-async.md.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .disableCachingNullValues()
                .prefixCacheNameWith("order-service:v1::");

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        "prices", defaults.entryTtl(Duration.ofSeconds(30)),
                        "dailySales", defaults.entryTtl(Duration.ofMinutes(10))))
                .transactionAware()
                .enableStatistics()          // hit/miss metrics at /actuator/metrics/cache.gets
                .build();
    }
}
