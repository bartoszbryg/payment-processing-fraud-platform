package main.repository;

import main.databaseModel.MerchantBlacklist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantBlacklistRepository extends JpaRepository<MerchantBlacklist, String> {
    
    // Called on every payment to check whether the merchant is blocked
    // Returns a boolean, hits the unique index on merchant_name

    boolean existsByMerchantName(String merchantName);

    // Admin lookup: fetch full entry when an analyst wants to see the reason or who added it
    Optional<MerchantBlacklist> findByMerchantName(String merchantName);
    
}
