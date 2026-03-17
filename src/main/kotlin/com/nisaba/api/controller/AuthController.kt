package com.nisaba.api.controller

import com.nisaba.api.auth.SessionStore
import com.nisaba.config.NisabaProperties
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/auth")
class AuthController(
    private val sessionStore: SessionStore,
    private val properties: NisabaProperties
) {

    @PostMapping("/login", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun login(
        @RequestParam username: String,
        @RequestParam password: String,
        response: HttpServletResponse
    ): String {
        if (username == properties.auth.username && password == properties.auth.password) {
            val sid = sessionStore.createSession()
            val cookie = Cookie("SID", sid)
            cookie.path = "/"
            cookie.isHttpOnly = true
            response.addCookie(cookie)
            return "Ok."
        }
        return "Fails."
    }
}
