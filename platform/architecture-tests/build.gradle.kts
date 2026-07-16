plugins {
    id("rwms.java25")
}

description = "RWMS platform and service architecture policy tests"

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(project(":platform:technical-contracts"))
    testImplementation(project(":services:auth-service"))
    testImplementation(project(":services:task-board-service"))
    testImplementation(project(":services:warehouse-service"))
    testImplementation(project(":services:asset-service"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.jakarta.persistence.api)
    testImplementation(libs.lombok)
    testImplementation(libs.mapstruct)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("rwms.root.dir", rootProject.projectDir.absolutePath)
}
