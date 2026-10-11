import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("mussolene", "nuvio-hdrezka-provider", "github")
        authors = listOf("mussolene", "Hexated", "NivinCNC")
    }

    android {
        namespace = "local.hdrezka"
        defaultConfig {
            minSdk = 23
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        val testImplementation by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.16")
        implementation("org.jsoup:jsoup:1.22.1")
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.20")
        add("compileOnly", "com.fasterxml.jackson.core:jackson-databind:2.17.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        testImplementation("junit:junit:4.13.2")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
