rootProject.extra.apply {
    set("androidPluginVersion", "7.0.4")
    set("kotlinVersion", "1.6.10")
    set("hutoolVersion", "5.7.18")
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}
