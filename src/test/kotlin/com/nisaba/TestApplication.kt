package com.nisaba

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<NisabaApplication>().with(TestcontainersConfiguration::class).run(*args)
}
