package dev.buhanzaz.rwms.inventory.eventing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryOutboxProperties.class)
class InventoryEventingConfiguration {}
