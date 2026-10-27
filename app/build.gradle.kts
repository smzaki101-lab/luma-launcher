plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    namespace = "dev.luma.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.luma.launcher"
        minSdk = 23
        targetSdk = 35
        versionCode = 56
        versionName = "1.0.12"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    val releaseStoreFile = providers.environmentVariable("LUMA_RELEASE_STORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("LUMA_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("LUMA_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("LUMA_RELEASE_KEY_PASSWORD").orNull
    val signingValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    require(signingValues.all { it == null } || signingValues.all { !it.isNullOrBlank() }) {
        "Set all four LUMA_RELEASE_* signing variables, or leave all four unset for an unsigned release build."
    }
    if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs {
            create("lumaRelease") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.names.contains("lumaRelease")) signingConfig = signingConfigs.getByName("lumaRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}

val localMenuAssets = layout.buildDirectory.dir("generated/localMenuAssets")
val bundleLocalMenu by tasks.registering {
    val names = listOf("luma-menu-bridge.sh", "luma-menu-control.sh")
    inputs.files(names.map { rootProject.file("tools/p6-menu-bridge/$it") })
    outputs.dir(localMenuAssets)
    doLast {
        val folder = localMenuAssets.get().dir("p6-menu-bridge").asFile.apply { mkdirs() }
        names.forEach { name -> folder.resolve(name).writeText(rootProject.file("tools/p6-menu-bridge/$name").readText().replace("\r\n", "\n")) }
    }
}
android.sourceSets.getByName("main").assets.srcDir(localMenuAssets)
tasks.named("preBuild").configure { dependsOn(bundleLocalMenu) }
