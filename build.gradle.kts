import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.0.13"
}

group = "ru.hse"
version = "1.0.0-SNAPSHOT"

// ----------------------------------------------------------------------
// ТЗ 4.6.2: в манифест JAR добавляются ФИО автора, версия, дата сборки,
// контрольная сумма (git build hash). Если git недоступен (zip-исходники
// без репозитория) — graceful fallback на "unknown".
// ----------------------------------------------------------------------
val authorName = "Нурмагомедов Радамир Ренатович, БПИ246"

val buildHash: String = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short=12", "HEAD")
        standardOutput = out
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    val s = out.toString(Charsets.UTF_8).trim()
    if (s.isEmpty()) "unknown" else s
} catch (_: Throwable) {
    "unknown"
}

val buildTime: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

val sharedManifestAttrs: Map<String, String> = mapOf(
    "Implementation-Title"   to "JBlockStorage",
    "Implementation-Version" to project.version.toString(),
    "Implementation-Vendor"  to authorName,
    "Build-Hash"             to buildHash,
    "Build-Time"             to buildTime,
    "Build-Jdk"              to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})"
)

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
            mapOf("Main-Class" to "ru.hse.jblockstorage.gui.Launcher")
                    + sharedManifestAttrs
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

// ----------------------------------------------------------------------
// Стандартный jar (без зависимостей) — тот же расширенный манифест
// (ТЗ 4.6.2: ФИО автора, версия, дата, контрольная сумма).
// ----------------------------------------------------------------------
tasks.jar {
    manifest {
        attributes(sharedManifestAttrs)
    }
}

// ----------------------------------------------------------------------
// Javadoc как «Текст программы» (ГОСТ 19.401-78,
// шифр RU.17701729.10.05-01 12 01-1).
// Запуск:    ./gradlew javadoc
// Результат: build/docs/javadoc/index.html
// ----------------------------------------------------------------------
tasks.javadoc {
    title = "JBlockStorage 1.0.0-SNAPSHOT — Текст программы (RU.17701729.10.05-01 12 01-1)"
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet("UTF-8")
        docEncoding = "UTF-8"
        locale = "ru_RU"
        windowTitle = "JBlockStorage — Текст программы"
        header("<b>JBlockStorage</b><br>Нурмагомедов Р.Р., БПИ246")
        bottom(
            "Курсовой проект, НИУ ВШЭ, 2026 г. — " +
                    "Шифр документа: RU.17701729.10.05-01 12 01-1"
        )
        // Полностью отключаем doclint: учебный проект, не библиотека.
        // Передаём флаг как отдельный аргумент (а не addBooleanOption,
        // который добавляет "=true" и ломает синтаксис).
        addStringOption("Xdoclint:none", "-quiet")
        // Не падать на ошибках линкования внешних классов.
        addStringOption("Xmaxwarns", "1")
        // Java 17 link
        links("https://docs.oracle.com/en/java/javase/17/docs/api/")
    }
    // Подавить даже фатальные ошибки парсера комментариев — иначе
    // невалидный HTML в одном файле срывает всю генерацию.
    isFailOnError = false
}