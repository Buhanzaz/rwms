package dev.buhanzaz.rwms.dossier.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled relay and recovery work outside test profiles without creating a separate workflow owner. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
class DossierSchedulingConfiguration {}
