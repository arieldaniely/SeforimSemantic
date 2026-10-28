import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("multiplatform") version "2.3.20"
}

group = "io.github.arieldaniely"
version = "0.1.0"

tasks.register<JavaExec>("benchmarkSemanticSearch") {
    group = "verification"
    description = "Compare optional retrieval modes on an externally supplied semantic index"
    dependsOn("jvmJar")
    mainClass.set("io.github.kdroidfilter.seforimlibrary.search.BenchmarkSemanticSearchKt")
    classpath = files(tasks.named("jvmJar")) + configurations.getByName("jvmRuntimeClasspath")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                providers.gradleProperty("semanticModelDir").get(),
                providers.gradleProperty("semanticIndexDir").get(),
                providers.gradleProperty("semanticReport").get(),
                providers.gradleProperty("benchmarkQueries").orElse("3").get(),
                providers.gradleProperty("benchmarkRepetitions").orElse("3").get(),
            )
        },
    )
    maxHeapSize = "4g"
    jvmArgs("--add-modules=jdk.incubator.vector")
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation("org.apache.lucene:lucene-core:10.5.2")
            implementation("org.apache.lucene:lucene-analysis-common:10.5.2")
            implementation("org.jsoup:jsoup:1.22.1")
            implementation("co.touchlab:kermit:2.1.0")
            implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
            implementation("ai.djl.huggingface:tokenizers:0.36.0")
            runtimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")
        }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
