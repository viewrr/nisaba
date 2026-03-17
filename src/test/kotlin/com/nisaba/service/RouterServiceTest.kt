package com.nisaba.service

import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.repository.NodeRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RouterServiceTest {

    private val nodeRepository = mockk<NodeRepository>()
    private val routerService = RouterService(nodeRepository)

    private fun node(id: String, emaWeight: Float = 0.5f) = NodeEntity(
        nodeId = id,
        baseUrl = "http://$id:8080",
        healthy = true,
        emaWeight = emaWeight
    )

    @Test
    fun `selectNode returns node with highest EMA weight`() {
        val nodes = listOf(
            node("node-a", 0.3f),
            node("node-b", 0.9f),
            node("node-c", 0.6f)
        )
        every { nodeRepository.findByHealthyTrue() } returns nodes

        val result = routerService.selectNode()

        result.isRight() shouldBe true
        result.getOrNull()!!.nodeId shouldBe "node-b"
    }

    @Test
    fun `selectNode excludes specified node`() {
        val nodes = listOf(
            node("node-a", 0.9f),
            node("node-b", 0.6f)
        )
        every { nodeRepository.findByHealthyTrue() } returns nodes

        val result = routerService.selectNode(excludeNodeId = "node-a")

        result.isRight() shouldBe true
        result.getOrNull()!!.nodeId shouldBe "node-b"
    }

    @Test
    fun `selectNode returns NoHealthyNodes when list is empty`() {
        every { nodeRepository.findByHealthyTrue() } returns emptyList()

        val result = routerService.selectNode()

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.NoHealthyNodes>()
    }

    @Test
    fun `selectNode returns NoHealthyNodes when all nodes are excluded`() {
        val nodes = listOf(node("node-a", 0.9f))
        every { nodeRepository.findByHealthyTrue() } returns nodes

        val result = routerService.selectNode(excludeNodeId = "node-a")

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.NoHealthyNodes>()
    }
}
