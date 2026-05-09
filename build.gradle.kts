plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.0.13"
}

group = "ru.hse"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 1. Jackson (JSON)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

    // 2. Netty (Сеть)
    implementation("io.netty:netty-all:4.1.100.Final")

    // 3. Bouncy Castle (Криптография)
    implementation("org.bouncycastle:bcprov-jdk18on:1.76")

    // 4. RocksDB (База данных)
    implementation("org.rocksdb:rocksdbjni:7.10.2")

    // 5. Логирование
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Тесты
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

javafx {
    version = "17.0.18"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    // День 12: единая точка входа Launcher разводит запуск:
    //   - без аргументов → JavaFX GUI;
    //   - с аргументами   → CLI (как было).
    // Это оставляет работающим `./gradlew run` (GUI), `./gradlew run --args="generate-keys ..."`
    // (CLI) и `java -jar fat.jar generate-keys ...` (CLI) одновременно.
    mainClass.set("ru.hse.jblockstorage.gui.Launcher")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8"
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ----------------------------------------------------------------------
// Удобный shortcut: `./gradlew runCli --args="generate-keys --output=node.keys --password=pin"`
// эквивалентен явному вызову CLI без необходимости пересобирать fat-jar.
// ----------------------------------------------------------------------
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Запуск CLI напрямую (минуя GUI-Launcher)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ru.hse.jblockstorage.cli.Main")
    standardInput = System.`in`
}

// ----------------------------------------------------------------------
// Executable fat-JAR без shadow-плагина (зависимостей хватает) —
// собирает все классы (свои + всех jar'ов из runtimeClasspath) в одну
// директорию и пакует в jar с правильным манифестом.
//
// Главный класс — Launcher: с аргументами → CLI, без аргументов → GUI.
//
// Использование:
//   ./gradlew fatJar
//   java -jar build/libs/JBlockStorage-fat.jar generate-keys --output=node.keys --password=pin
//   java -jar build/libs/JBlockStorage-fat.jar         # запуск GUI
// ----------------------------------------------------------------------
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("JBlockStorage")
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "ru.hse.jblockstorage.gui.Launcher",
            "Implementation-Title" to "JBlockStorage",
            "Implementation-Version" to project.version
        )
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Исключаем подписи и метаданные модулей, которые ломаются при объединении
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/MANIFEST.MF", "module-info.class")
    }
}
