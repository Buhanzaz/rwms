package dev.buhanzaz.rwms.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile

class RwmsMapStructConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("rwms.java25")

            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            dependencies.add("compileOnly", libs.findLibrary("mapstruct").orElseThrow())
            dependencies.add(
                "annotationProcessor",
                libs.findLibrary("mapstruct-processor").orElseThrow(),
            )
            dependencies.add(
                "annotationProcessor",
                libs.findLibrary("lombok-mapstruct-binding").orElseThrow(),
            )
            dependencies.add(
                "testAnnotationProcessor",
                libs.findLibrary("mapstruct-processor").orElseThrow(),
            )
            dependencies.add(
                "testAnnotationProcessor",
                libs.findLibrary("lombok-mapstruct-binding").orElseThrow(),
            )

            tasks.withType(JavaCompile::class.java).configureEach {
                options.compilerArgs.addAll(
                    listOf(
                        "-Amapstruct.defaultComponentModel=spring",
                        "-Amapstruct.defaultInjectionStrategy=constructor",
                        "-Amapstruct.unmappedTargetPolicy=ERROR",
                        "-Amapstruct.suppressGeneratorTimestamp=true",
                        "-Amapstruct.suppressGeneratorVersionInfoComment=true",
                    ),
                )
            }
        }
    }
}
