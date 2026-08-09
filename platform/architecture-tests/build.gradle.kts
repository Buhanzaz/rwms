import org.gradle.api.tasks.testing.Test

plugins {
    id("rwms.java25")
}

description = "RWMS platform and service architecture policy tests"

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.cloud.dependencies))
    testImplementation(project(":platform:technical-contracts"))
    testImplementation(project(":services:auth-service"))
    testImplementation(project(":services:task-board-service"))
    testImplementation(project(":services:warehouse-service"))
    testImplementation(project(":services:asset-service"))
    testImplementation(project(":services:maintenance-service"))
    if (rootProject.findProject(":services:inventory-service") != null) {
        testImplementation(project(":services:inventory-service"))
    }
    if (rootProject.findProject(":services:logistics-service") != null) {
        testImplementation(project(":services:logistics-service"))
    }
    if (rootProject.findProject(":services:dossier-service") != null) {
        testImplementation(project(":services:dossier-service"))
    }
    testImplementation(project(":services:assistant-service"))
    testImplementation(project(":services:analytics-service"))
    testImplementation(project(":services:api-gateway-service"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jakarta.persistence.api)
    testImplementation(libs.lombok)
    testImplementation(libs.mapstruct)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
    testImplementation("org.springframework:spring-beans")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework.data:spring-data-commons")
    testImplementation("org.springframework.kafka:spring-kafka")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("rwms.root.dir", rootProject.projectDir.absolutePath)
}

val canonicalContractIntegrityTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Validates every canonical OpenAPI, event catalog and JSON Schema source"
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("dev.buhanzaz.rwms.contracts.CanonicalContractIntegrityTest")
    }
    systemProperty("rwms.root.dir", rootProject.projectDir.absolutePath)
}
