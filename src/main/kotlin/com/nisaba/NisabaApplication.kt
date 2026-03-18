package com.nisaba

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main Spring Boot application class.
 * 
 * The application entry point is in NisabaLauncher.java to ensure
 * proper AOT initializer class naming for GraalVM native images.
 * 
 * @see NisabaLauncher
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
open class NisabaApplication
