package main.repository;

import main.databaseModel.MerchantBlacklist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantBlacklistRepository extends JpaRepository<MerchantBlacklist, String> {
    
    // Called on every payment - only active entries block a transaction, solf-deleted ones are ignored
    boolean existsByMerchantNameAndActiveTrue(String merchantName);

    // Admin lookup: fetch the active entry when an analyst wants to see the reason or who added it
    Optional<MerchantBlacklist> findByMerchantNameAndActiveTrue(String merchantName);

    // Admin lookup: needed to show the full audit history including removed entries
    Optional<MerchantBlacklist> findByMerchantName(String merchantName);
    
}
