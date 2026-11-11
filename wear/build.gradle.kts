import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

// Release signing. Must be the SAME upload key as the Notly phone app
// (notly-mobile-app/android/key.properties): the Wearable Data Layer only
// connects apps that share an applicationId AND signing certificate.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
  keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
  namespace = "dev.tberghuis.voicememos"
  compileSdk = 36

  defaultConfig {
    // Must equal the phone app's applicationId (incl. flavor suffix) so the
    // Data Layer can deliver messages/files between them. Published as the
    // Wear OS form factor of the same Play Console app.
    applicationId = "com.notly.app"
    minSdk = 30
    targetSdk = 36
    // Wear versionCodes live in their own range (>= 100000) so they never
    // collide with the phone app's versionCodes on the same Play listing.
    versionCode = 100001
    versionName = "1.0.0-wear"
  }

  // Mirrors notly-mobile-app/android/app/build.gradle.kts productFlavors.
  // Each watch flavor only talks to the phone flavor with the same suffix.
  flavorDimensions += "environment"
  productFlavors {
    create("internal") {
      dimension = "environment"
      applicationIdSuffix = ".test"
      resValue("string", "app_name", "Notly Watch Test")
    }
    create("uat") {
      dimension = "environment"
      applicationIdSuffix = ".uat"
      resValue("string", "app_name", "Notly Watch UAT")
    }
    create("live") {
      dimension = "environment"
      resValue("string", "app_name", "Notly Watch")
    }
  }

  signingConfigs {
    create("release") {
      keyAlias = keystoreProperties["keyAlias"] as String?
      keyPassword = keystoreProperties["keyPassword"] as String?
      storeFile = keystoreProperties["storeFile"]?.let { file(it) }
      storePassword = keystoreProperties["storePassword"] as String?
    }
  }

  buildTypes {
    release {
      if (keystorePropertiesFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = true
      // if isShrinkResources = true, 
      // capabilityClient.getCapability("wear", CapabilityClient.FILTER_REACHABLE).await().nodes
      // is empty when invoked on mobile
      // some sort of bug but isShrinkResources = false makes things work
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":common"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.compose.material3)


  androidTestImplementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.compose.foundation)
  implementation(libs.androidx.compose.material.iconsExtended)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.wear.input)
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.androidx.work.ktx)
  implementation(libs.androidx.wear.ongoing)
  implementation(libs.horologist.compose.layout)
  implementation(libs.androidx.core.splashscreen)

  // whats the deal???
//    wearApp(project(":wear"))

  implementation(libs.androidx.lifecycle.viewmodel.compose)
}