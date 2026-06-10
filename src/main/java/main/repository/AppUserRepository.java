package main.repository;

import main.databaseModel.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional; 

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    // Called by Spring Security's service on every JWT-authenticated request
    // Hits the unique index on username - must be fast!
    Optional<AppUser> findByUsername(String username);


    // Registration guard: Checked before inserting a new admin/analyst account
    boolean existsByUsername(String username);
    
}
