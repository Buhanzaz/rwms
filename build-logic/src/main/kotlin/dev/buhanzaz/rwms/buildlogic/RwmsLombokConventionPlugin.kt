package dev.buhanzaz.rwms.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class RwmsLombokConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("rwms.java25")

            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            val lombok = libs.findLibrary("lombok").orElseThrow()

            dependencies.add("compileOnly", lombok)
            dependencies.add("annotationProcessor", lombok)
            dependencies.add("testCompileOnly", lombok)
            dependencies.add("testAnnotationProcessor", lombok)
        }
    }
}
