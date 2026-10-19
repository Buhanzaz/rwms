plugins {
    id("rwms.spring-service")
}

description = "RWMS stateless API gateway"

dependencies {
    implementation(project(":platform:technical-contracts"))
    implementation(project(":platform:spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("rwms.test.runtime-classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("rwms.test.project-dir", projectDir.absolutePath)
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}
