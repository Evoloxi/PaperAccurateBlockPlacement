import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)
    alias(libs.plugins.paperweight)
    alias(libs.plugins.run.paper)
}

group = "me.evo"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.flyte.gg/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    /*maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.dmulloy2.net/repository/public/")*/
}

dependencies {
    paperweight.paperDevBundle("1.21.5-R0.1-SNAPSHOT")

    implementation(libs.twilight)
    implementation(libs.paperlib)

    implementation(libs.lamp.common)
    implementation(libs.lamp.bukkit)
    implementation(libs.lamp.brigadier)

    implementation(files(rootProject.file("libs/ProtocolLib.jar")))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    build { dependsOn(shadowJar) }
    runServer { minecraftVersion("1.21.5") }
    compileKotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            //javaParameters = true
        }
    }

    shadowJar {
        relocate("kotlin", "me.evo.kotlin")
        relocate("io.papermc.lib", "me.evo.paperlib")
        relocate("org.jetbrains.annotations", "me.evo.jetbrains.annotations")
        relocate("org.intellij.lang.annotations", "me.evo.intellij.lang.annotations")
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from({
            configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
        })
    }
}