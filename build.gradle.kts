import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    id("org.jetbrains.compose") version "1.5.10"
}

group = "com.workforboss"
version = "0.2.0"

repositories {
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.aliyun.com/repository/public")
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("media.kamel:kamel-image:0.9.1")
    implementation("org.slf4j:slf4j-simple:2.0.12")
    
    // JavaFX for AAC/M4A support
    val jfxVersion = "21"
    implementation("org.openjfx:javafx-media:$jfxVersion:win")
    implementation("org.openjfx:javafx-graphics:$jfxVersion:win")
    implementation("org.openjfx:javafx-base:$jfxVersion:win")
    implementation("org.openjfx:javafx-controls:$jfxVersion:win")
    implementation("org.openjfx:javafx-swing:$jfxVersion:win")
}

compose.desktop {
    application {
        mainClass = "com.workforboss.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "WFMusic"
            packageVersion = "0.2.0"
            description = "WFMusic Player"
            copyright = "© 2025 WFMusic. All rights reserved."
            vendor = "WFMusic"
            
            windows {
                shortcut = true
                menu = true
                menuGroup = "WFMusic"
                // upgradeUuid 建议固定一个，这样覆盖安装时能识别是同一个应用
                upgradeUuid = "8c6c9a30-3c1d-4d7e-9e8a-8c6c9a303c1d"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}

tasks.register<Zip>("packagePortable") {
    group = "compose desktop"
    description = "Packages the application as a portable zip file."
    dependsOn("createDistributable")
    from("build/compose/binaries/main/app")
    archiveFileName.set("WFMusic-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/zip"))
}
