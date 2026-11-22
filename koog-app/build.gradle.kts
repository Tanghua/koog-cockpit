plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "cockpit.validation.koog"
    compileSdk = 36
    defaultConfig { applicationId = "com.qwenaudio.validation.koog"; minSdk = 33; targetSdk = 33; versionCode = 5; versionName = "0.3.0" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":koog"));  }

dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.14.1") }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") }

dependencies { implementation(project(":cockpit-ui")) }
