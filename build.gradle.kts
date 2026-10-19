import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "dev.buhanzaz.rwms"
version = "0.0.1-SNAPSHOT"
description = "rwms"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

val approvedVersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val approvedDependencyVersions =
    mapOf(
        "org.springframework.boot:spring-boot" to
            approvedVersionCatalog.findVersion("spring-boot").orElseThrow().requiredVersion,
        "org.springframework.cloud:spring-cloud-dependencies" to
            approvedVersionCatalog.findVersion("spring-cloud").orElseThrow().requiredVersion,
        "org.springframework.cloud:spring-cloud-stream" to
            approvedVersionCatalog.findVersion("spring-cloud-stream").orElseThrow().requiredVersion,
        "org.springframework.cloud:spring-cloud-stream-binder-kafka" to
            approvedVersionCatalog.findVersion("spring-cloud-stream").orElseThrow().requiredVersion,
        "org.apache.kafka:kafka-clients" to
            approvedVersionCatalog.findVersion("kafka").orElseThrow().requiredVersion,
        "org.projectlombok:lombok" to
            approvedVersionCatalog.findVersion("lombok").orElseThrow().requiredVersion,
        "org.projectlombok:lombok-mapstruct-binding" to
            approvedVersionCatalog.findVersion("lombok-mapstruct-binding").orElseThrow().requiredVersion,
        "org.mapstruct:mapstruct" to
            approvedVersionCatalog.findVersion("mapstruct").orElseThrow().requiredVersion,
        "org.mapstruct:mapstruct-processor" to
            approvedVersionCatalog.findVersion("mapstruct").orElseThrow().requiredVersion,
    )

val verifyApprovedDependencyVersions by tasks.registering {
    group = "verification"
    description = "Verifies approved dependency versions across every Java 25 RWMS module"
    doLast {
        subprojects.forEach { candidate ->
            listOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
                "annotationProcessor",
                "testAnnotationProcessor",
            )
                .mapNotNull(candidate.configurations::findByName)
                .filter { it.isCanBeResolved }
                .forEach { configuration ->
                    configuration.allDependencies
                        .filterIsInstance<ExternalModuleDependency>()
                        .forEach dependency@{ dependency ->
                            val coordinate = "${dependency.group}:${dependency.name}"
                            val approved = approvedDependencyVersions[coordinate] ?: return@dependency
                            val requested = dependency.version
                            check(requested == null || requested == approved) {
                                "${candidate.path}:${configuration.name} directly requests " +
                                    "$coordinate:$requested; approved version is $approved"
                            }
                        }
                    configuration.incoming.resolutionResult.allComponents.forEach component@{ component ->
                        val identifier = component.id as? ModuleComponentIdentifier ?: return@component
                        val coordinate = "${identifier.group}:${identifier.module}"
                        val approved = approvedDependencyVersions[coordinate] ?: return@component
                        check(identifier.version == approved) {
                            "${candidate.path}:${configuration.name} resolved " +
                                "$coordinate:${identifier.version}; approved version is $approved"
                        }
                        if (configuration.name == "runtimeClasspath") {
                            check(coordinate !in setOf("org.mapstruct:mapstruct", "org.projectlombok:lombok")) {
                                "${candidate.path}:runtimeClasspath must not contain compile-time annotation library $coordinate"
                            }
                        }
                    }
                }
        }

        val technicalContracts = project(":platform:technical-contracts")
        val forbiddenTechnicalContractGroups =
            listOf(
                "org.springframework",
                "jakarta.persistence",
                "org.projectlombok",
                "org.mapstruct",
                "org.apache.kafka",
            )
        listOf("compileClasspath", "runtimeClasspath").forEach { configurationName ->
            technicalContracts.configurations
                .getByName(configurationName)
                .incoming
                .resolutionResult
                .allComponents
                .forEach component@{ component ->
                    val identifier = component.id as? ModuleComponentIdentifier ?: return@component
                    check(forbiddenTechnicalContractGroups.none(identifier.group::startsWith)) {
                        "platform:technical-contracts must remain framework-neutral but resolved " +
                            "${identifier.group}:${identifier.module}:${identifier.version}"
                    }
                }
        }
    }
}

val verifyCanonicalContracts by tasks.registering {
    group = "verification"
    description = "Runs the deterministic canonical OpenAPI, event catalog and JSON Schema integrity gate"
    dependsOn(":platform:architecture-tests:canonicalContractIntegrityTest")
}

subprojects {
    pluginManager.withPlugin("rwms.java25") {
        rootProject.tasks.named("verifyApprovedDependencyVersions").configure {
            dependsOn(tasks.named("verifyApprovedDependencyVersions"))
        }
    }
}
