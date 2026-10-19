package dev.buhanzaz.rwms.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled inventory relay and recovery workers.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
class InventorySchedulingConfiguration {}
