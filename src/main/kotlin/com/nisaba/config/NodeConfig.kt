package com.nisaba.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

data class NodeDefinition(
    val id: String,
    val url: String,
    val username: String,
    val password: String,
    val label: String? = null,
    val clientType: String = "qbittorrent"
)

data class NodeListWrapper(val nodes: List<NodeDefinition>)

@Configuration
class NodeConfig {
    @Bean
    fun nodeDefinitions(
        properties: NisabaProperties,
        resourceLoader: ResourceLoader
    ): List<NodeDefinition> {
        val yaml = YAMLMapper()
        val resource = resourceLoader.getResource(properties.nodesFile)
        val inputStream = if (resource.exists()) {
            resource.inputStream
        } else {
            File(properties.nodesFile).inputStream()
        }
        return yaml.readValue(inputStream, NodeListWrapper::class.java).nodes
    }
}
