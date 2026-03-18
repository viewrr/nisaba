package com.nisaba.api.controller

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppControllerTest {

    private val controller = AppController()

    @Test
    fun `version returns expected version string`() {
        val result = controller.version()

        result shouldBe "v4.6.7"
    }

    @Test
    fun `webapiVersion returns expected API version`() {
        val result = controller.webapiVersion()

        result shouldBe "2.9.3"
    }

    @Test
    fun `preferences returns default preferences`() {
        val result = controller.preferences()

        // QBitPreferences should be returned with default values
        result shouldBe com.nisaba.api.dto.QBitPreferences()
    }
}
