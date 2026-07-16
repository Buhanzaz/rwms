package dev.buhanzaz.rwms.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RwmsConventionPluginsFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `spring service convention pins Java and annotation processing policy`() {
        writeFixtureProject()

        val result = runGradle("verifyF4kConventions", "verifyApprovedDependencyVersions")

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyF4kConventions")?.outcome)
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":verifyApprovedDependencyVersions")?.outcome,
        )
    }

    @Test
    fun `approved dependency verification rejects a silently resolved direct drift`() {
        writeFixtureProject()
        projectDir.resolve("build.gradle.kts").writeText(
            projectDir.resolve("build.gradle.kts").readText() +
                "\ndependencies { implementation(\"org.mapstruct:mapstruct:1.6.2\") }\n",
        )

        val failure =
            org.junit.jupiter.api.Assertions.assertThrows(UnexpectedBuildFailure::class.java) {
                runGradle("verifyApprovedDependencyVersions")
            }

        assertTrue(failure.message.orEmpty().contains("approved version is 1.6.3"))
    }

    @Test
    fun `lombok and mapstruct processors produce deterministic incremental output`() {
        writeFixtureProject()
        writeAnnotationProcessingFixture()

        val firstBuild = runGradle("clean", "compileJava", "--no-build-cache")
        val generatedMapper =
            projectDir.resolve(
                "build/generated/sources/annotationProcessor/java/main/fixture/mapper/FixtureMapperImpl.java",
            )

        assertEquals(TaskOutcome.SUCCESS, firstBuild.task(":compileJava")?.outcome)
        assertTrue(generatedMapper.exists(), "MapStruct must generate the mapper implementation")
        assertTrue(
            projectDir.resolve("build/classes/java/main/fixture/LombokProjection.class").exists(),
            "Lombok-backed source must compile",
        )
        val firstGeneratedSource = generatedMapper.readText()
        assertFalse(firstGeneratedSource.contains("date ="), "generated source must not contain a timestamp")
        assertFalse(
            firstGeneratedSource.contains("version:"),
            "generated source must not expose the generator version",
        )

        val incrementalBuild = runGradle("compileJava", "--no-build-cache")
        assertEquals(TaskOutcome.UP_TO_DATE, incrementalBuild.task(":compileJava")?.outcome)

        val cleanRebuild = runGradle("clean", "compileJava", "--no-build-cache")
        assertEquals(TaskOutcome.SUCCESS, cleanRebuild.task(":compileJava")?.outcome)
        assertEquals(firstGeneratedSource, generatedMapper.readText())
    }

    private fun runGradle(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--max-workers=1", *arguments, "--stacktrace")
            .build()

    private fun writeFixtureProject() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        projectDir.resolve("gradle").createDirectories()
        projectDir.resolve("gradle/libs.versions.toml").writeText(
            """
            [versions]
            spring-boot = "4.1.0"
            spring-cloud = "2025.1.2"
            spring-cloud-stream = "5.0.2"
            kafka = "4.3.1"
            lombok = "1.18.46"
            mapstruct = "1.6.3"
            lombok-mapstruct-binding = "0.2.0"

            [libraries]
            spring-cloud-dependencies = { module = "org.springframework.cloud:spring-cloud-dependencies", version.ref = "spring-cloud" }
            spring-cloud-stream-binder-kafka = { module = "org.springframework.cloud:spring-cloud-stream-binder-kafka", version.ref = "spring-cloud-stream" }
            kafka-clients = { module = "org.apache.kafka:kafka-clients", version.ref = "kafka" }
            lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
            mapstruct = { module = "org.mapstruct:mapstruct", version.ref = "mapstruct" }
            mapstruct-processor = { module = "org.mapstruct:mapstruct-processor", version.ref = "mapstruct" }
            lombok-mapstruct-binding = { module = "org.projectlombok:lombok-mapstruct-binding", version.ref = "lombok-mapstruct-binding" }
            """.trimIndent(),
        )
        Files.createDirectories(projectDir.resolve("src/main/java"))
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import org.gradle.api.plugins.JavaPluginExtension
            import org.gradle.api.tasks.compile.JavaCompile

            plugins {
                id("rwms.spring-service")
                id("rwms.mapstruct")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.springframework.boot:spring-boot")
                implementation("org.springframework.cloud:spring-cloud-stream")
                implementation(libs.spring.cloud.stream.binder.kafka)
            }

            tasks.register("verifyF4kConventions") {
                doLast {
                    check(project.pluginManager.hasPlugin("org.springframework.boot"))
                    check(project.pluginManager.hasPlugin("io.spring.dependency-management"))
                    check(project.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion.get().asInt() == 25)

                    val compileOnly = project.configurations.getByName("compileOnly").dependencies.associateBy { it.name }
                    check(compileOnly.getValue("lombok").version == "1.18.46")

                    val processors = project.configurations.getByName("annotationProcessor").dependencies.associateBy { it.name }
                    check(processors.getValue("lombok").version == "1.18.46")
                    check(processors.getValue("mapstruct-processor").version == "1.6.3")
                    check(processors.getValue("lombok-mapstruct-binding").version == "0.2.0")

                    val args = project.tasks.named<JavaCompile>("compileJava").get().options.compilerArgs
                    check("-parameters" in args)
                    check("-Amapstruct.defaultComponentModel=spring" in args)
                    check("-Amapstruct.defaultInjectionStrategy=constructor" in args)
                    check("-Amapstruct.unmappedTargetPolicy=ERROR" in args)
                    check("-Amapstruct.suppressGeneratorTimestamp=true" in args)
                    check("-Amapstruct.suppressGeneratorVersionInfoComment=true" in args)

                    val resolved = project.configurations.getByName("compileClasspath")
                        .resolvedConfiguration.resolvedArtifacts
                        .associate { "${'$'}{it.moduleVersion.id.group}:${'$'}{it.name}" to it.moduleVersion.id.version }
                    check(resolved.getValue("org.springframework.boot:spring-boot") == "4.1.0")
                    check(resolved.getValue("org.springframework.cloud:spring-cloud-stream") == "5.0.2")
                    check(resolved.getValue("org.apache.kafka:kafka-clients") == "4.3.1")

                    val runtime = project.configurations.getByName("runtimeClasspath")
                        .resolvedConfiguration.resolvedArtifacts
                        .map { "${'$'}{it.moduleVersion.id.group}:${'$'}{it.name}" }
                        .toSet()
                    check("org.mapstruct:mapstruct" !in runtime)
                    check("org.projectlombok:lombok" !in runtime)
                }
            }
            """.trimIndent(),
        )
    }

    private fun writeAnnotationProcessingFixture() {
        val sourceRoot = projectDir.resolve("src/main/java/fixture")
        sourceRoot.resolve("mapper").createDirectories()
        sourceRoot.resolve("LombokProjection.java").writeText(
            """
            package fixture;

            import lombok.Value;

            @Value
            public class LombokProjection {
              String code;
            }
            """.trimIndent(),
        )
        sourceRoot.resolve("FixtureDto.java").writeText(
            """
            package fixture;

            public record FixtureDto(String code) {}
            """.trimIndent(),
        )
        sourceRoot.resolve("mapper/FixtureMapper.java").writeText(
            """
            package fixture.mapper;

            import fixture.FixtureDto;
            import fixture.LombokProjection;
            import org.mapstruct.Mapper;

            @Mapper
            public interface FixtureMapper {
              FixtureDto toDto(LombokProjection source);
            }
            """.trimIndent(),
        )
    }
}
