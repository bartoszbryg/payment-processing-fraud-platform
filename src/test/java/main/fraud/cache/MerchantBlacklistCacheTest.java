package main.fraud.cache;

import main.config.CacheConfig;
import main.databaseModel.MerchantBlacklist;
import main.repository.MerchantBlacklistRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests MerchantBlacklistCache with the real Spring cache proxy enabled.
 *
 * Uses a small Spring context:
 * - CacheConfig provides the cache manager
 * - MerchantBlacklistCache is the bean under test
 * - MerchantBlacklistRepository is mocked, so no database is needed
 *
 * These tests verify:
 * - @Cacheable caches blacklist lookups
 * - empty Optional results are cached too
 * - @CacheEvict clears only the selected merchant entry
 * - the merchantBlacklist cache is created on demand
 */
@SpringJUnitConfig(classes = {CacheConfig.class, MerchantBlacklistCache.class})
class MerchantBlacklistCacheTest {

    @Autowired
    private MerchantBlacklistCache blacklistCache;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    MerchantBlacklistRepository blacklistRepository;

    @BeforeEach
    void clearCache() {
        Objects.requireNonNull(cacheManager.getCache("merchantBlacklist")).clear();
    }

    private MerchantBlacklist entry(String name, String reason) {
        return MerchantBlacklist.builder()
            .merchantName(name)
            .reason(reason)
            .addedBy("analyst01")
            .build();
    }

    @Test
    void cacheHit_repositoryCalledOnlyOnce() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("ShadyStore"))
            .thenReturn(Optional.of(entry("ShadyStore", "Multiple chargebacks")));

        Optional<MerchantBlacklist> first = blacklistCache.findActive("ShadyStore");
        Optional<MerchantBlacklist> second = blacklistCache.findActive("ShadyStore");

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals("Multiple chargebacks", second.get().getReason());

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("ShadyStore");
    }

    @Test
    void cacheMiss_emptyOptionalIsCached() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("CleanStore"))
            .thenReturn(Optional.empty());

        Optional<MerchantBlacklist> first = blacklistCache.findActive("CleanStore");
        Optional<MerchantBlacklist> second = blacklistCache.findActive("CleanStore");

        assertFalse(first.isPresent());
        assertFalse(second.isPresent());

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("CleanStore");
    }

    @Test
    void distinctMerchantNamesAreCachedIndependently() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("StoreA"))
            .thenReturn(Optional.of(entry("StoreA", "reason A")));

        when(blacklistRepository.findByMerchantNameAndActiveTrue("StoreB"))
            .thenReturn(Optional.of(entry("StoreB", "reason B")));

        blacklistCache.findActive("StoreA");
        blacklistCache.findActive("StoreB");
        blacklistCache.findActive("StoreA");
        blacklistCache.findActive("StoreB");

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("StoreA");

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("StoreB");
    }

    @Test
    void evict_clearsEntry_nextCallHitsRepositoryAgain() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("ShadyStore"))
            .thenReturn(Optional.of(entry("ShadyStore", "original reason")));

        blacklistCache.findActive("ShadyStore");
        blacklistCache.evict("ShadyStore");

        when(blacklistRepository.findByMerchantNameAndActiveTrue("ShadyStore"))
            .thenReturn(Optional.empty());

        Optional<MerchantBlacklist> afterEvict = blacklistCache.findActive("ShadyStore");

        assertFalse(afterEvict.isPresent());

        verify(blacklistRepository, times(2))
            .findByMerchantNameAndActiveTrue("ShadyStore");
    }

    @Test
    void evict_doesNotAffectOtherCacheEntries() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("StoreA"))
            .thenReturn(Optional.of(entry("StoreA", "reason A")));

        when(blacklistRepository.findByMerchantNameAndActiveTrue("StoreB"))
            .thenReturn(Optional.of(entry("StoreB", "reason B")));

        blacklistCache.findActive("StoreA");
        blacklistCache.findActive("StoreB");

        blacklistCache.evict("StoreA");

        blacklistCache.findActive("StoreA");
        blacklistCache.findActive("StoreB");

        verify(blacklistRepository, times(2))
            .findByMerchantNameAndActiveTrue("StoreA");

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("StoreB");
    }

    @Test
    void dynamicCacheCreation_merchantBlacklistCacheIsCreatedOnDemand() {
        when(blacklistRepository.findByMerchantNameAndActiveTrue("AnyMerchant"))
            .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> blacklistCache.findActive("AnyMerchant"));

        verify(blacklistRepository, times(1))
            .findByMerchantNameAndActiveTrue("AnyMerchant");
    }
}