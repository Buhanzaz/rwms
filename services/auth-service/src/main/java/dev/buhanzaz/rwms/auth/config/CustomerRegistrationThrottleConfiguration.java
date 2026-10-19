package dev.buhanzaz.rwms.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables validated external configuration for anonymous-registration abuse budgets. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerRegistrationThrottleProperties.class)
public class CustomerRegistrationThrottleConfiguration {}
