package com.nisaba.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.atomic.AtomicBoolean

@Component
class BootGate : WebMvcConfigurer {

    companion object {
        private val logger = LoggerFactory.getLogger(BootGate::class.java)
    }

    private val ready = AtomicBoolean(false)

    fun open() {
        ready.set(true)
        logger.info("BootGate opened - API is now accepting requests")
    }

    fun isReady(): Boolean = ready.get()

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(
                request: HttpServletRequest,
                response: HttpServletResponse,
                handler: Any
            ): Boolean {
                if (!ready.get()) {
                    response.status = 503
                    response.writer.write("Service is starting up, please wait...")
                    return false
                }
                return true
            }
        }).addPathPatterns("/api/v2/**")
    }
}
