package com.nisaba

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main Spring Boot application class.
 * 
 * The main() function is in the companion object with @JvmStatic,
 * which creates a static main method in NisabaApplication class itself.
 * This ensures AOT initializer lookup works correctly since both
 * @SpringBootApplication and main() are in the same class.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
open class NisabaApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<NisabaApplication>(*args)
        }
    }
}
