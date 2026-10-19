plugins {
    id("rwms.spring-service")
    id("rwms.mapstruct")
}

description = "RWMS task board and workforce service"

dependencies {
    implementation(project(":platform:technical-contracts"))
    implementation(project(":platform:spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.spring.cloud.stream)
    implementation(libs.spring.cloud.stream.binder.kafka)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("com.google.firebase:firebase-admin:9.10.0")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.yaml:snakeyaml")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
    testImplementation(libs.spring.cloud.stream.test.binder)
    testImplementation(libs.archunit.junit5)
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation(libs.testcontainers.kafka)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    test {
        resources.srcDir("database")
    }
}

tasks.test {
    systemProperty("rwms.contracts.dir", rootProject.file("contracts").absolutePath)
    // Keep the shared PostgreSQL container and JVM within one explicit full-suite budget.
    maxParallelForks = 1
    maxHeapSize = "768m"
    systemProperty("spring.test.context.cache.maxSize", "1")
}
