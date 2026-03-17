package com.nisaba.api.controller

import com.nisaba.api.dto.QBitPreferences
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/app")
class AppController {

    @GetMapping("/version", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun version(): String = "v4.6.7"

    @GetMapping("/webapiVersion", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun webapiVersion(): String = "2.9.3"

    @GetMapping("/preferences")
    fun preferences(): QBitPreferences = QBitPreferences()
}
