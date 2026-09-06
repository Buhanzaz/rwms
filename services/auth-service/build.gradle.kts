plugins {
    id("rwms.spring-service")
    id("rwms.mapstruct")
}

description = "RWMS authorization and identity service"

val authUiDirectory = layout.projectDirectory.dir("ui")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val installAuthUi by tasks.registering(Exec::class) {
    workingDir(authUiDirectory)
    commandLine(npmExecutable, "ci")
    inputs.files(authUiDirectory.file("package.json"), authUiDirectory.file("package-lock.json"))
    outputs.dir(authUiDirectory.dir("node_modules"))
}

val buildAuthUi by tasks.registering(Exec::class) {
    dependsOn(installAuthUi)
    workingDir(authUiDirectory)
    commandLine(npmExecutable, "run", "build")
    inputs.files(
        authUiDirectory.file("package.json"),
        authUiDirectory.file("package-lock.json"),
        authUiDirectory.file("index.html"),
        authUiDirectory.file("vite.config.ts"),
        authUiDirectory.file("tsconfig.json"),
        authUiDirectory.file("tsconfig.app.json"),
        authUiDirectory.file("tsconfig.node.json"),
    )
    inputs.dir(authUiDirectory.dir("src"))
    inputs.dir(authUiDirectory.dir("public"))
    outputs.dir(authUiDirectory.dir("dist"))
}

dependencies {
    implementation(project(":platform:technical-contracts"))
    implementation(project(":platform:spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.spring.cloud.stream)
    implementation(libs.spring.cloud.stream.binder.kafka)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
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
        resources.srcDir("database/baseline")
        resources.srcDir("database/flyway")
        resources.srcDir("database/releases")
    }
}

tasks.withType<Test> {
    systemProperty("rwms.contracts.dir", rootProject.file("contracts").absolutePath)
}

tasks.processResources {
    dependsOn(buildAuthUi)
    from(authUiDirectory.dir("dist")) {
        into("static")
    }
}
