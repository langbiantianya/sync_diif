plugins {
    kotlin("jvm") version "2.4.20"
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect
    implementation(kotlin("reflect"))

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation(project(":core"))

    testImplementation(kotlin("test"))
    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}

kotlin {
    jvmToolchain(17)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}
