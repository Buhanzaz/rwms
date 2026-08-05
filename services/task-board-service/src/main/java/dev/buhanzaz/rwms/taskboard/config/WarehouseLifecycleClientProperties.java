package dev.buhanzaz.rwms.taskboard.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Private warehouse-service endpoint used only for lifecycle admission and readiness. */
@Validated
@ConfigurationProperties("rwms.warehouse.lifecycle")
public record WarehouseLifecycleClientProperties(@NotNull URI baseUrl) {}
