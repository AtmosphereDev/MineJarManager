plugins {
    id("java")

    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.atmosphere"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.formdev:flatlaf-intellij-themes:3.7.2")
    implementation("com.formdev:flatlaf-extras:3.7.2")
}

tasks.shadowJar {
    manifest {
        attributes(
            "Main-Class" to "dev.atmosphere.minejarmanager.PluginManagerApp"
        )
    }
}