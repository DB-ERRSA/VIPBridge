plugins {
    java
}

group = "com.errsa"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("de.oliver:FancyNpcs:2.9.2")
    compileOnly("de.oliver:FancyHolograms:2.9.1")

    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    compileOnly(files("libs/ImageFrame-2026.1.2.0.jar"))
    compileOnly(files("libs/HeadDB-6.0.3.jar"))
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("VIPBridge")
    }
}