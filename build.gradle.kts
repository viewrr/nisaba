plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.10.6"
	jacoco
}

group = "com.nisaba"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot starters
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// Arrow-kt
	implementation("io.arrow-kt:arrow-core:2.1.2")
	implementation("io.arrow-kt:arrow-fx-coroutines:2.1.2")

	// Kotlin coroutines
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// Jackson / YAML parsing for nodes.yml
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

	// Database
	implementation("org.postgresql:postgresql")

	// Configuration processor
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	// Test
	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("io.mockk:mockk:1.14.2")
	testImplementation("io.kotest:kotest-assertions-core:6.0.0.M2")
	testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("nisaba")
			mainClass.set("com.nisaba.NisabaApplicationKt")
			buildArgs.addAll(
				"--verbose",
				"-H:+ReportExceptionStackTraces",
				"--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback",
				"--initialize-at-run-time=io.netty"
			)
		}
	}
	toolchainDetection.set(false)
}

// Configure AOT processing to use datasource from environment variables
// This is needed for native image builds where AOT processing requires a running database
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
	// Use jvmArgumentProviders for lazy evaluation at execution time
	jvmArgumentProviders.add(CommandLineArgumentProvider {
		val datasourceUrl = System.getenv("SPRING_DATASOURCE_URL")
		val datasourceUsername = System.getenv("SPRING_DATASOURCE_USERNAME") ?: "postgres"
		val datasourcePassword = System.getenv("SPRING_DATASOURCE_PASSWORD") ?: "postgres"
		
		if (!datasourceUrl.isNullOrBlank()) {
			listOf(
				"-Dspring.datasource.url=$datasourceUrl",
				"-Dspring.datasource.username=$datasourceUsername",
				"-Dspring.datasource.password=$datasourcePassword",
				"-Dspring.datasource.driver-class-name=org.postgresql.Driver"
			)
		} else {
			emptyList()
		}
	})
}
