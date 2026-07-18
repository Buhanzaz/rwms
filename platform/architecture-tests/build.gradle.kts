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
    testImplementation(project(":services:maintenance-service"))
    if (rootProject.findProject(":services:inventory-service") != null) {
        testImplementation(project(":services:inventory-service"))
    }
    if (rootProject.findProject(":services:logistics-service") != null) {
        testImplementation(project(":services:logistics-service"))
    }
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
