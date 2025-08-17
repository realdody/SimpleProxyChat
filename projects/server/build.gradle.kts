import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

version = project.property("version") as String

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven {
        name = "JCenter"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Spigot. Duh.
    compileOnly("org.spigotmc", "spigot-api", "1.21.8-R0.1-SNAPSHOT")

    // PlaceholderAPI
    compileOnly("me.clip", "placeholderapi", "2.11.6")

    // bStats
    implementation("org.bstats", "bstats-bukkit", "3.1.0")

    // Mock Testing
    testImplementation("org.mockbukkit.mockbukkit", "mockbukkit-v1.21", "4.72.6")

    // Artifact Version Comparison
    // TODO: Eventually remove this.
    implementation("org.apache.maven", "maven-artifact", "3.9.11")
}

configure<ProcessResources>("processResources") {
    // Capture tokens at configuration time to avoid Task.project access during execution (Gradle 10)
    val versionToken = project.version.toString()
    inputs.property("version", versionToken)
    val tokens = mapOf("version" to versionToken)

    filesMatching("plugin.yml") {
        expand(tokens)
    }
}

inline fun <reified C> Project.configure(name: String, configuration: C.() -> Unit) {
    (this.tasks.getByName(name) as C).configuration()
}

tasks.withType<ShadowJar> {
    relocate("org.bstats", "com.beanbeanjuice.simpleproxychathelper.libs.org.bstats")
    relocate("org.apache.maven", "com.beanbeanjuice.simpleproxychathelper.libs.org.apache.maven")
}
