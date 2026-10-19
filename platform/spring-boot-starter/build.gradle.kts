plugins {
    `java-library`
    id("rwms.java25")
    id("io.spring.dependency-management")
}

description = "RWMS technical Spring Boot starter"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
        mavenBom(libs.spring.cloud.dependencies.get().toString())
    }
}

dependencies {
    api(project(":platform:technical-contracts"))
    api("org.springframework.boot:spring-boot-autoconfigure")

    compileOnly("org.springframework.boot:spring-boot-starter-jackson")
    compileOnly("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly(libs.spring.cloud.stream)
    compileOnly(libs.spring.cloud.stream.binder.kafka)
    compileOnly("io.micrometer:micrometer-observation")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation(libs.spring.cloud.stream.binder.kafka)
    testImplementation(libs.spring.cloud.stream.test.binder)
    testImplementation("io.micrometer:micrometer-observation")
    testImplementation("jakarta.persistence:jakarta.persistence-api")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation(libs.testcontainers.kafka)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("rwms.test.runtime-classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
