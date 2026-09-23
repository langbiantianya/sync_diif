plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

dependencies {
    // CheckRegistry.discover() 的反射 (KClass.objectInstance) 需要 kotlin-reflect
    implementation(kotlin("reflect"))

    // 协程库只有测试用得到（runBlocking 驱动 suspend 的 Check.run）；`suspend` 本身是语言特性，
    // 主源码不需要它。真正的运行时依赖由 app 声明。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation(project(":core"))

    testImplementation(kotlin("test"))
    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
}
