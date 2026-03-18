package com.nisaba

/**
 * Legacy application class - kept for compatibility.
 * 
 * The actual Spring Boot application is now NisabaLauncher.java which
 * contains both @SpringBootApplication and main() to ensure proper
 * AOT initializer class naming for GraalVM native images.
 * 
 * @see NisabaLauncher
 */
@Deprecated("Use NisabaLauncher instead", ReplaceWith("NisabaLauncher"))
open class NisabaApplication
