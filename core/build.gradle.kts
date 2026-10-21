plugins {
    kotlin("jvm")
}


dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    // gemeinsame Testvektoren mit der Python-Referenzimplementierung
    systemProperty("vectors", rootProject.file("protocol/test-vectors.json").absolutePath)
}
