package com.nisaba.api.controller

import com.nisaba.api.dto.QBitCategory
import com.nisaba.config.NisabaProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/torrents")
class CategoryController(
    private val properties: NisabaProperties
) {

    @GetMapping("/categories")
    fun categories(): Map<String, QBitCategory> {
        return properties.categories.map { (name, path) ->
            name to QBitCategory(name = name, savePath = path)
        }.toMap()
    }

    @PostMapping("/createCategory")
    fun createCategory(): ResponseEntity<String> = ResponseEntity.ok("Ok.")

    @PostMapping("/setCategory")
    fun setCategory(): ResponseEntity<String> = ResponseEntity.ok("Ok.")
}
