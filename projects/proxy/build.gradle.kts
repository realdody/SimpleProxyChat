import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

version = project.property("version") as String

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java {
            // Exclude all Bungee sources from compilation
            exclude("com/beanbeanjuice/simpleproxychat/SimpleProxyChatBungee.java")
            exclude("com/beanbeanjuice/simpleproxychat/commands/bungee/**")
            exclude("com/beanbeanjuice/simpleproxychat/socket/bungee/**")
            exclude("com/beanbeanjuice/simpleproxychat/utility/listeners/bungee/**")
        }
        resources {
            // Do not package Bungee descriptor
            exclude("bungee.yml")
        }
    }
    test {
        java {
            exclude("**/bungee/**")
            exclude("**/bungeecord/**")
        }
    }
}

dependencies {
    // Velocity
    compileOnly("com.velocitypowered", "velocity-api", "3.4.0-SNAPSHOT")
    testImplementation("com.velocitypowered", "velocity-api", "3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered", "velocity-api", "3.4.0-SNAPSHOT")

    // Bungee
    // Remove Bungee from build; keep Adventure core libs for Velocity
    implementation("net.kyori", "adventure-api", "4.24.0")
    implementation("net.kyori", "adventure-text-minimessage", "4.24.0")
    implementation("net.kyori", "adventure-text-serializer-plain", "4.24.0")
    implementation("net.kyori", "adventure-text-serializer-legacy", "4.24.0")
    implementation("net.kyori", "adventure-text-serializer-gson", "4.24.0")

    // Discord Support
    implementation("net.dv8tion", "JDA", "5.6.1") {
        exclude(module = "opus-java")
    }

    // PremiumVanish/SuperVanish Support
    compileOnly("com.github.LeonMangler", "PremiumVanishAPI", "2.9.18-2")

    // Better YAML Support
    implementation("dev.dejvokep", "boosted-yaml", "1.3.7")

    // bStats
    implementation("org.bstats", "bstats-velocity", "3.1.0")

    // LuckPerms Support
    compileOnly("net.luckperms", "api", "5.4")

    // LiteBans Support
    compileOnly("com.gitlab.ruany", "LiteBansAPI", "0.6.1")

    // AdvancedBan Support (exclude Bungee module and its bstats dependency)
    compileOnly("com.github.DevLeoko", "AdvancedBan", "v2.3.0") {
        exclude(group = "com.github.DevLeoko.AdvancedBan", module = "AdvancedBan-Bungee")
        exclude(group = "org.bstats", module = "bstats-bungeecord")
    }

    // NetworkManager Support
    compileOnly("nl.chimpgamer.networkmanager", "api", "2.17.9")

    // Spicord Support
    compileOnly("org.spicord", "spicord-common", "5.7.2")

    // Timestamp
    implementation("joda-time", "joda-time", "2.14.0")

    // YepLib (Velocity helper for backend plugin messages) - optional at runtime
    // JitPack coordinates: artifactId equals archives_base_name from YepLib ('yeplib')
    // Latest release tag as of now: 2.4.0
    // Repo: https://github.com/yeahimman/YepLib
    compileOnly("com.github.yeahimman", "yeplib", "2.4.0")

    // Artifact Version Comparison
    // TODO: Eventually remove this.
    implementation("org.apache.maven", "maven-artifact", "3.9.11")
}

configure<ProcessResources>("processResources") {
    // Capture tokens at configuration time to avoid Task.project access during execution (Gradle 10)
    val versionToken = project.version.toString()
    inputs.property("version", versionToken)
    val tokens = mapOf("version" to versionToken)

    filesMatching("velocity-plugin.json") {
        expand(tokens)
    }
}

inline fun <reified C> Project.configure(name: String, configuration: C.() -> Unit) {
    (this.tasks.getByName(name) as C).configuration()
}

tasks.withType<ShadowJar> {
    relocate("net.dv8tion", "com.beanbeanjuice.simpleproxychat.libs.net.dv8tion")
    relocate("dev.dejvokep", "com.beanbeanjuice.simpleproxychat.libs.dev.dejvokep")
    relocate("org.bstats", "com.beanbeanjuice.simpleproxychat.libs.org.bstats")
    relocate("joda-time", "com.beanbeanjuice.simpleproxychat.libs.joda-time")  // check
    relocate("org.apache.maven", "com.beanbeanjuice.simpleproxychat.libs.org.apache.maven")  // check
    // merge SPI service files for libraries that rely on them
    mergeServiceFiles()
}
