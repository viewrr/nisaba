package com.nisaba.api.controller

import com.nisaba.config.NisabaProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class CategoryControllerTest {

    private lateinit var properties: NisabaProperties
    private lateinit var controller: CategoryController

    @BeforeEach
    fun setUp() {
        properties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("user", "pass"),
            categories = mapOf(
                "movies" to "/data/movies",
                "tv" to "/data/tv",
                "default" to "/data/media"
            )
        )
        controller = CategoryController(properties)
    }

    @Test
    fun `categories returns all configured categories`() {
        val result = controller.categories()

        result.size shouldBe 3
        result["movies"]?.name shouldBe "movies"
        result["movies"]?.savePath shouldBe "/data/movies"
        result["tv"]?.name shouldBe "tv"
        result["tv"]?.savePath shouldBe "/data/tv"
        result["default"]?.name shouldBe "default"
        result["default"]?.savePath shouldBe "/data/media"
    }

    @Test
    fun `categories returns empty map when no categories configured`() {
        val emptyProperties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("user", "pass"),
            categories = emptyMap()
        )
        val emptyController = CategoryController(emptyProperties)

        val result = emptyController.categories()

        result.size shouldBe 0
    }

    @Test
    fun `createCategory returns ok`() {
        val result = controller.createCategory()

        result.statusCode shouldBe HttpStatus.OK
        result.body shouldBe "Ok."
    }

    @Test
    fun `setCategory returns ok`() {
        val result = controller.setCategory()

        result.statusCode shouldBe HttpStatus.OK
        result.body shouldBe "Ok."
    }
}
