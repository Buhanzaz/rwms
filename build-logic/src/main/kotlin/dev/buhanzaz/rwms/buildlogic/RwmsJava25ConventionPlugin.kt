package dev.buhanzaz.rwms.buildlogic

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

class RwmsJava25ConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("java")

            extensions.configure(JavaPluginExtension::class.java) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(25))
                sourceCompatibility = JavaVersion.VERSION_25
                targetCompatibility = JavaVersion.VERSION_25
            }

            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            val approvedVersions =
                mapOf(
                    "org.springframework.boot:spring-boot" to
                        libs.findVersion("spring-boot").orElseThrow().requiredVersion,
                    "org.springframework.cloud:spring-cloud-stream" to
                        libs.findVersion("spring-cloud-stream").orElseThrow().requiredVersion,
                    "org.springframework.cloud:spring-cloud-stream-binder-kafka" to
                        libs.findVersion("spring-cloud-stream").orElseThrow().requiredVersion,
                    "org.springframework.cloud:spring-cloud-dependencies" to
                        libs.findVersion("spring-cloud").orElseThrow().requiredVersion,
                    "org.apache.kafka:kafka-clients" to
                        libs.findVersion("kafka").orElseThrow().requiredVersion,
                    "org.projectlombok:lombok" to
                        libs.findVersion("lombok").orElseThrow().requiredVersion,
                    "org.mapstruct:mapstruct" to
                        libs.findVersion("mapstruct").orElseThrow().requiredVersion,
                    "org.mapstruct:mapstruct-processor" to
                        libs.findVersion("mapstruct").orElseThrow().requiredVersion,
                    "org.projectlombok:lombok-mapstruct-binding" to
                        libs.findVersion("lombok-mapstruct-binding").orElseThrow().requiredVersion,
                )
            libs.findLibrary("kafka-clients").ifPresent { kafkaClients ->
                val approvedKafkaClient = kafkaClients.get()
                dependencies.constraints.add("implementation", kafkaClients) {
                    version { strictly(approvedKafkaClient.versionConstraint.requiredVersion) }
                    because("RWMS F4K pins the Kafka protocol client independently of the binder BOM")
                }
                pluginManager.withPlugin("io.spring.dependency-management") {
                    extensions.configure(DependencyManagementExtension::class.java) {
                        dependencies {
                            dependency(
                                "${approvedKafkaClient.module}:"
                                    + approvedKafkaClient.versionConstraint.requiredVersion,
                            )
                        }
                    }
                }
            }

            tasks.withType(JavaCompile::class.java).configureEach {
                options.encoding = "UTF-8"
                options.compilerArgs.add("-parameters")
            }

            tasks.withType(Test::class.java).configureEach {
                useJUnitPlatform()
            }

            tasks.register("verifyApprovedDependencyVersions") {
                group = "verification"
                description =
                    "Verifies approved F4K versions on the real compile, runtime, test and processor graphs"
                doLast {
                    verifyApprovedDependencyVersions(approvedVersions)
                }
            }
        }
    }

    private fun Project.verifyApprovedDependencyVersions(approvedVersions: Map<String, String>) {
        val relevantConfigurations =
            listOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
                "annotationProcessor",
                "testAnnotationProcessor",
            )

        relevantConfigurations
            .mapNotNull(configurations::findByName)
            .filter { it.isCanBeResolved }
            .forEach { configuration ->
                configuration.allDependencies
                    .filterIsInstance<ExternalModuleDependency>()
                    .forEach dependency@{ dependency ->
                        val coordinate = "${dependency.group}:${dependency.name}"
                        val approved = approvedVersions[coordinate] ?: return@dependency
                        val requested = dependency.version
                        check(requested == null || requested == approved) {
                            "$path:${configuration.name} directly requests $coordinate:$requested; approved version is $approved"
                        }
                    }

                configuration.incoming.resolutionResult.allComponents.forEach component@{ component ->
                    val identifier = component.id as? ModuleComponentIdentifier ?: return@component
                    val coordinate = "${identifier.group}:${identifier.module}"
                    val approved = approvedVersions[coordinate] ?: return@component
                    check(identifier.version == approved) {
                        "$path:${configuration.name} resolved $coordinate:${identifier.version}; approved version is $approved"
                    }
                }
            }
    }
}
