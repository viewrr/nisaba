plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.10.6"
	id("io.gitlab.arturbosch.detekt") version "1.23.7"
	id("org.owasp.dependencycheck") version "12.1.1"
	jacoco
}

group = "com.nisaba"
version = "0.0.1-SNAPSHOT"

java {
	// Use source/target compatibility instead of toolchain to support both
	// JDK 21 (local dev, JVM CI) and GraalVM 25 (native image CI)
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

// Ensure Java compilation targets Java 21 bytecode even on newer JDKs
tasks.withType<JavaCompile>().configureEach {
	options.release.set(21)
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
	
	// H2 for AOT processing only - used during native image compilation
	// Spring Boot AOT needs a datasource; H2 provides one without a running server
	runtimeOnly("com.h2database:h2")

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
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
			mainClass.set("com.nisaba.NisabaLauncher")
			buildArgs.addAll(
				"--verbose",
				"-H:+ReportExceptionStackTraces",
				"--initialize-at-build-time=org.slf4j",
				"--initialize-at-build-time=ch.qos.logback",
				"--initialize-at-run-time=io.netty"
			)
		}
	}
	toolchainDetection.set(false)
}

// Configure main class for Spring Boot and GraalVM native image
// Using a Java launcher class avoids Kotlin companion object naming issues
springBoot {
	mainClass.set("com.nisaba.NisabaLauncher")
}

// Detekt configuration
detekt {
	buildUponDefaultConfig = true
	config.setFrom(files("$projectDir/detekt.yml"))
}

// OWASP Dependency Check configuration
dependencyCheck {
	failBuildOnCVSS = 9.0f  // Only fail on critical vulnerabilities
	suppressionFile = "$projectDir/owasp-suppressions.xml"
}

// AOT processing uses H2 in-memory database via the 'aot' profile.
// Migrations are SQL-standard compatible with both H2 and PostgreSQL.
// Build with: SPRING_PROFILES_ACTIVE=aot ./gradlew nativeCompile
