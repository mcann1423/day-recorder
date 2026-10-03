plugins {
  id("com.android.library")
}

android {
  namespace = "com.example.dayrecorder.updatecore"
  compileSdk = 36

  defaultConfig {
    minSdk = 29
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
  testImplementation(libs.junit)
}
