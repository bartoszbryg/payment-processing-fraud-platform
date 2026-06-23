package main.fraud.cache;

import lombok.RequiredArgsConstructor;
import main.databaseModel.MerchantBlacklist;
import main.repository.MerchantBlacklistRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Separate bean so @Cacheable calls go through the Spring AOP proxy.
 *
 * Self-invocation problem: if BlacklistedMerchantRule called its own @Cacheable method,
 * the call would go directly to 'this' — bypassing the proxy — and the cache would never
 * be populated. Extracting the lookup here ensures every call is an inter-bean call that
 * passes through the AOP interceptor.
 */
@Service
@RequiredArgsConstructor
public class MerchantBlacklistCache {

    private final MerchantBlacklistRepository blacklistRepository;

    @Cacheable(value = "merchantBlacklist", key = "#merchantName")
    public Optional<MerchantBlacklist> findActive(String merchantName) {
        return blacklistRepository.findByMerchantNameAndActiveTrue(merchantName);
    }

    // Call this whenever an admin adds, updates, or deactivates a blacklist entry
    @CacheEvict(value = "merchantBlacklist", key = "#merchantName")
    public void evict(String merchantName) {} // Method intentionally empty: annotation performs the cache eviction
}