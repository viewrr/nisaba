rootProject.name = "nisaba"

// Enable build cache for faster incremental builds
buildCache {
    local {
        isEnabled = true
        directory = file("${rootDir}/.gradle/build-cache")
    }
}
