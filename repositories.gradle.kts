repositories {
    // --- China mirrors (aliyun) ---
    maven(url = "https://maven.aliyun.com/repository/central")
    maven(url = "https://maven.aliyun.com/repository/google")
    maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
    // --- Primary repos (fallback) ---
    google()
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
}