package com.nisaba

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class NisabaApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<NisabaApplication>(*args)
        }
    }
}
