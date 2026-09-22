plugins { id("com.android.application") }

android {
    namespace = "io.github.kuuminkochi.bigfatfish"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.kuuminkochi.bigfatfish"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val releaseStore = providers.environmentVariable("BIGFATFISH_KEYSTORE").orNull
    if (releaseStore != null) {
        signingConfigs.create("privateRelease") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("BIGFATFISH_KEYSTORE_PASSWORD").get()
            keyAlias = "bigfatfish"
            keyPassword = providers.environmentVariable("BIGFATFISH_KEYSTORE_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("privateRelease")
        }
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
