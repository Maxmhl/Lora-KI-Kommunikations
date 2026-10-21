plugins {
    kotlin("jvm")
}


dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.tukaani:xz:1.10")
    compileOnly("org.json:json:20240303") // auf Android Teil der Plattform
    // zstd: auf Android als AAR (im App-Modul), auf der JVM (Tests) mit mitgelieferten Desktop-Bibliotheken
    compileOnly("com.github.luben:zstd-jni:1.5.6-6")
    testImplementation("com.github.luben:zstd-jni:1.5.6-6")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    // gemeinsame Testvektoren mit der Python-Referenzimplementierung
    systemProperty("vectors", rootProject.file("protocol/test-vectors.json").absolutePath)
    // Mini-Wikipedia aus Project NOMAD (siehe docs/ENTWICKLUNG.md); ohne Datei werden die ZIM-Tests übersprungen
    (System.getenv("LK_TEST_ZIM") ?: rootProject.file("core/testdata/wikipedia_en_100_mini.zim").takeIf { it.exists() }?.absolutePath)
        ?.let { systemProperty("zimFile", it) }
}

// Entwicklungshilfe: ZIM-Datei wie in der App ausliefern (für Browser-Tests): gradle :core:zimDevServer -PzimFile=...
tasks.register<JavaExec>("zimDevServer") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("de.loraki.core.ZimDevServerKt")
    args(project.findProperty("zimFile")?.toString() ?: "", project.findProperty("zimPort")?.toString() ?: "8098")
}
