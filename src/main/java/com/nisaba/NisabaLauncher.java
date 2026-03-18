package com.nisaba;

import org.springframework.boot.SpringApplication;

/**
 * Java-based application launcher for GraalVM native image compatibility.
 * 
 * This ensures the main class name matches what Spring Boot AOT generates,
 * avoiding issues with Kotlin's companion object class naming.
 */
public class NisabaLauncher {
    public static void main(String[] args) {
        SpringApplication.run(NisabaApplication.class, args);
    }
}
