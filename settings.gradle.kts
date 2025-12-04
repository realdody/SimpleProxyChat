rootProject.name = "SimpleProxyChat"

include(
    "projects/proxy",
    "projects/server"
)

project(":projects/proxy").name = "SimpleProxyChat"
project(":projects/server").name = "SimpleProxyChatHelper"

// Ensure JitPack is available to dependency resolution even if Gradle prefers settings repositories
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
