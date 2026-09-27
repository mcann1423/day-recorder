plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.example.dayrecorder"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example.dayrecorder"
    minSdk = 29
    targetSdk = 36
    versionCode = 3
    versionName = "1.2"
  }

  val releaseKeystorePath = providers.environmentVariable("DAY_RECORDER_KEYSTORE_PATH").orNull
  val releaseKeystorePassword = providers.environmentVariable("DAY_RECORDER_KEYSTORE_PASSWORD").orNull
  val releaseKeyAlias = providers.environmentVariable("DAY_RECORDER_KEY_ALIAS").orNull
  val releaseKeyPassword = providers.environmentVariable("DAY_RECORDER_KEY_PASSWORD").orNull
  signingConfigs {
    if (
      releaseKeystorePath != null && releaseKeystorePassword != null &&
      releaseKeyAlias != null && releaseKeyPassword != null
    ) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation("androidx.documentfile:documentfile:1.1.0")
  implementation(libs.play.services.wearable)
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
