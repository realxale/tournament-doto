plugins {
    application
    java
    id("org.flywaydb.flyway") version "10.17.0"
}

group = "com.pocketsage"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Discord
    implementation("net.dv8tion:JDA:6.5.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.2")

    // БД: драйвер + пул соединений
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Миграции
    implementation("org.flywaydb:flyway-core:10.17.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.17.0")

    // Логи
    implementation("ch.qos.logback:logback-classic:1.5.8")

    // .env
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // Тесты
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    // Gradle 9 больше не подкладывает launcher сам — объявляем явно
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.testcontainers:postgresql:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
}

application {
    mainClass.set("com.pocketsage.tournament.Main")
}

tasks.test {
    useJUnitPlatform()
}
