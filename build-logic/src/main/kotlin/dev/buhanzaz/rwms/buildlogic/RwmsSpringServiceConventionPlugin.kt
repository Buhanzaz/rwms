package dev.buhanzaz.rwms.buildlogic

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class RwmsSpringServiceConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("rwms.java25")
        pluginManager.apply("rwms.lombok")
        pluginManager.apply("org.springframework.boot")
        pluginManager.apply("io.spring.dependency-management")

        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val cloudBom = libs.findLibrary("spring-cloud-dependencies").orElseThrow().get()
        extensions.configure(DependencyManagementExtension::class.java) {
            imports {
                mavenBom("${cloudBom.module}:${cloudBom.versionConstraint.requiredVersion}")
            }
        }
    }
}
