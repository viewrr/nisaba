package com.nisaba.api.controller

import com.nisaba.api.auth.SessionStore
import com.nisaba.config.NisabaProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.*
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthControllerTest {

    private lateinit var sessionStore: SessionStore
    private lateinit var properties: NisabaProperties
    private lateinit var response: HttpServletResponse
    private lateinit var controller: AuthController

    @BeforeEach
    fun setUp() {
        sessionStore = mockk()
        response = mockk(relaxed = true)
        properties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("admin", "secret123")
        )
        controller = AuthController(sessionStore, properties)
    }

    @Test
    fun `login returns Ok and sets cookie on valid credentials`() {
        every { sessionStore.createSession() } returns "test-session-id"

        val result = controller.login("admin", "secret123", response)

        result shouldBe "Ok."
        verify { sessionStore.createSession() }
        verify {
            response.addCookie(match { cookie ->
                cookie.name == "SID" &&
                cookie.value == "test-session-id" &&
                cookie.path == "/" &&
                cookie.isHttpOnly
            })
        }
    }

    @Test
    fun `login returns Fails on invalid username`() {
        val result = controller.login("wronguser", "secret123", response)

        result shouldBe "Fails."
        verify(exactly = 0) { sessionStore.createSession() }
        verify(exactly = 0) { response.addCookie(any()) }
    }

    @Test
    fun `login returns Fails on invalid password`() {
        val result = controller.login("admin", "wrongpass", response)

        result shouldBe "Fails."
        verify(exactly = 0) { sessionStore.createSession() }
        verify(exactly = 0) { response.addCookie(any()) }
    }

    @Test
    fun `login returns Fails on both invalid credentials`() {
        val result = controller.login("wrong", "wrong", response)

        result shouldBe "Fails."
        verify(exactly = 0) { sessionStore.createSession() }
    }
}
