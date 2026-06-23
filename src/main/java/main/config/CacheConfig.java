package main.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

/**
 * Cache and scheduling configuration.
 *
 * @EnableCaching activates @Cacheable/@CacheEvict interceptors.
 * Without it, cache annotations are ignored.
 *
 * @EnableScheduling activates @Scheduled method execution.
 * Without it, scheduled graph rebuilds will not run.
 *
 * Both annotations live here instead of Main.java so they are close to the
 * infrastructure beans they enable.
 */
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("merchantBlacklist");

        manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(5_000));

        return manager;
    }
}