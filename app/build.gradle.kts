import com.android.build.api.variant.Component
import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/** Canonical sources are inputs, never content roots shared by multiple Android Studio modules. */
@CacheableTask
abstract class StageKotlinSources @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        fileSystemOperations.sync {
            from(inputDirectory)
            into(outputDirectory)
        }
    }
}

fun stageKotlinSources(component: Component, canonicalDirectory: String) {
    val stage = tasks.register<StageKotlinSources>(component.computeTaskName("stage", "SharedSources")) {
        inputDirectory.set(layout.projectDirectory.dir(canonicalDirectory))
    }
    // AGP assigns a distinct build/generated output for this task/component and wires Kotlin compilation.
    requireNotNull(component.sources.kotlin).addGeneratedSourceDirectory(stage, StageKotlinSources::outputDirectory)
    // KSP 2.3.9's built-in Kotlin integration reads static roots, omitting generated Kotlin roots.
    // Pass this producer-backed file tree explicitly for both processing and symbol resolution.
    // Do not use all component sources: that would also include KSP's own outputs and create a cycle.
    val stagedSources = stage.flatMap { it.outputDirectory }.map { it.asFileTree }
    val kspTaskName = component.computeTaskName("ksp", "Kotlin")
    tasks.withType<KspAATask>().configureEach {
        if (name == kspTaskName) {
            kspConfig.sourceRoots.from(stagedSources)
            kspConfig.javaSourceRoots.from(stagedSources)
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        // Build-type initWith/matchingFallbacks do not share sources. Keep devDebug isolated.
        if (variant.buildType in setOf("debug", "release")) {
            stageKotlinSources(variant, "src/productionShared/java")
        }
        // Each enabled runner owns its generated root; pure policies never enter the app APK.
        variant.hostTests.values.forEach { stageKotlinSources(it, "src/sharedTest/java") }
        variant.deviceTests.values.forEach { stageKotlinSources(it, "src/sharedTest/java") }
    }
}

android {
    namespace = "pl.bargor.thesaurus"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.bargor.thesaurus"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AGP creates local/device test tasks for one build type at a time.
    // Keep existing CI on debug unless developer-mode tests are explicitly requested.
    testBuildType = if (providers.gradleProperty("devTest").orNull == "true") "devDebug" else "debug"

    buildTypes {
        create("devDebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("devDebugImplementation", libs.androidx.compose.ui.test.manifest)
}
