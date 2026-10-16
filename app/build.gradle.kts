import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.auramediaplayer.v3.ppqtdt"
    minSdk = 24
    targetSdk = 36
    versionCode = 6
    versionName = "1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    multiDexEnabled = true
    multiDexKeepFile = file("multidex-keep.txt")
    multiDexKeepProguard = file("multidex-config.pro")

    // [REMEDIATION] 16KB Page-Size Alignment Support (P1)
    // Ensures compatibility with Android 15/16 high-performance devices.
    packaging {
      jniLibs {
        useLegacyPackaging = true
      }
    }
  }

  assetPacks += mutableSetOf(":model_pack")

  flavorDimensions += "edition"
  productFlavors {
    create("consumer") {
      dimension = "edition"
      buildConfigField("boolean", "ENABLE_DEVELOPER_TOOLS", "true")
      buildConfigField("String", "BUILD_FLAVOR_NAME", "\"consumer\"")
    }
    create("developer") {
      dimension = "edition"
      applicationIdSuffix = ".developer"
      buildConfigField("boolean", "ENABLE_DEVELOPER_TOOLS", "true")
      buildConfigField("String", "BUILD_FLAVOR_NAME", "\"developer\"")
    }
  }

  signingConfigs {
    create("release") {
      val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
          localPropsFile.inputStream().use { stream -> load(stream) }
        }
      }

      val keystorePath = System.getenv("KEYSTORE_PATH")
        ?: (project.findProperty("KEYSTORE_PATH") as? String)
        ?: localProps.getProperty("KEYSTORE_PATH")
        ?: "C:/Users/lance/Desktop/Keystore/Google Play Credentials/Keystore/aura-play-upload-2026.jks"

      val storePass = System.getenv("STORE_PASSWORD")
        ?: (project.findProperty("STORE_PASSWORD") as? String)
        ?: localProps.getProperty("STORE_PASSWORD")

      val keyPass = System.getenv("KEY_PASSWORD")
        ?: (project.findProperty("KEY_PASSWORD") as? String)
        ?: localProps.getProperty("KEY_PASSWORD")

      val aliasName = System.getenv("KEY_ALIAS")
        ?: (project.findProperty("KEY_ALIAS") as? String)
        ?: localProps.getProperty("KEY_ALIAS")
        ?: "key0"

      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists() && !storePass.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
        storeFile = keystoreFile
        storePassword = storePass
        keyAlias = aliasName
        keyPassword = keyPass
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      val releaseSigningConfig = signingConfigs.getByName("release")
      if (releaseSigningConfig.storeFile != null && releaseSigningConfig.storePassword != null) {
        signingConfig = releaseSigningConfig
      } else {
        // Enforce fail-closed check via task graph execution hook
        gradle.taskGraph.whenReady {
          val requiresReleaseSigning = allTasks.any { task ->
            val path = task.path
            val name = task.name
            val isReleaseTask = path.contains("Release", ignoreCase = true) || name.contains("Release", ignoreCase = true)
            val isAggregateTask = (name == "assemble" || name == "build" || name == "bundle") &&
                                  !path.contains("Debug", ignoreCase = true) &&
                                  !path.contains("Test", ignoreCase = true)

            (isReleaseTask || isAggregateTask) &&
            !path.contains("Debug", ignoreCase = true) &&
            !path.contains("Test", ignoreCase = true) &&
            !path.contains("Lint", ignoreCase = true)
          }
          if (requiresReleaseSigning) {
            throw GradleException("FATAL: Production release signing credentials (keystore, storePassword, keyPassword) are missing. Release builds and aggregate packaging tasks cannot be signed with debug keys or unsigned.")
          }
        }
      }
    }
    debug {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  sourceSets {
    getByName("main") {
      assets.setSrcDirs(listOf("src/main/assets"))
    }
  }
}

gradle.taskGraph.whenReady {
  val hasReleaseTask = hasTask(":app:bundleConsumerRelease") ||
                       hasTask(":app:bundleDeveloperRelease") ||
                       hasTask(":app:assembleConsumerRelease") ||
                       hasTask(":app:assembleDeveloperRelease")
  val releaseConfig = extensions.findByType(com.android.build.api.dsl.ApplicationExtension::class.java)?.signingConfigs?.findByName("release")
  if (hasReleaseTask && (releaseConfig?.storeFile == null || releaseConfig?.storePassword == null)) {
    logger.warn(
      "Release signing configuration is incomplete or keystore is missing. Falling back to debug signing configuration."
    )
  }
}

kotlin {
  jvmToolchain(21)
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation("androidx.exifinterface:exifinterface:1.3.7")
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.extractor)
  implementation(libs.androidx.media3.container)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.google.billing)
  implementation(libs.sqlcipher)
  implementation(libs.onnxruntime.android)
  implementation(libs.google.play.asset.delivery)
  implementation(libs.google.play.asset.delivery.ktx)
  testImplementation(libs.onnxruntime)
  implementation(libs.coil.compose)
  implementation(libs.coil.video)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
