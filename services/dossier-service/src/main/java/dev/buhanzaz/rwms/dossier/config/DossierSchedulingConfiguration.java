package dev.buhanzaz.rwms.dossier.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
class DossierSchedulingConfiguration {}
