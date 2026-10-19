plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("rwmsJava25") {
            id = "rwms.java25"
            implementationClass = "dev.buhanzaz.rwms.buildlogic.RwmsJava25ConventionPlugin"
        }
        create("rwmsLombok") {
            id = "rwms.lombok"
            implementationClass = "dev.buhanzaz.rwms.buildlogic.RwmsLombokConventionPlugin"
        }
        create("rwmsMapStruct") {
            id = "rwms.mapstruct"
            implementationClass = "dev.buhanzaz.rwms.buildlogic.RwmsMapStructConventionPlugin"
        }
        create("rwmsSpringService") {
            id = "rwms.spring-service"
            implementationClass = "dev.buhanzaz.rwms.buildlogic.RwmsSpringServiceConventionPlugin"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
