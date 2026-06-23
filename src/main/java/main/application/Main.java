package main.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "main")
// Caching and scheduling are enabled in CacheConfig
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}