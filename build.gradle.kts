plugins {
    kotlin("multiplatform") version "2.3.20"
}

group = "io.github.arieldaniely"
version = "0.1.0"

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
