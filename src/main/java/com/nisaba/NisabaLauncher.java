package com.nisaba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Spring Boot application entry point.
 * 
 * This Java class serves as both the application class and launcher,
 * ensuring AOT initializer class naming matches for GraalVM native images.
 * 
 * Spring Boot AOT generates initializers based on the @SpringBootApplication
 * annotated class, and runtime looks for initializers based on the class
 * that contains main(). Having both in the same class resolves the mismatch.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NisabaLauncher {
    public static void main(String[] args) {
        SpringApplication.run(NisabaLauncher.class, args);
    }
}
