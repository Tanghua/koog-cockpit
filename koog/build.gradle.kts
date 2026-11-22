plugins { kotlin("jvm"); kotlin("plugin.serialization") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":contract"))
    implementation("ai.koog:agents-core:1.3.0")
    implementation("ai.koog:prompt-executor-model:1.3.0")
    implementation("ai.koog:prompt-executor-openai-client:1.3.0")
    implementation("ai.koog:agents-features-snapshot:1.3.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped"); showStandardStreams = true } }
