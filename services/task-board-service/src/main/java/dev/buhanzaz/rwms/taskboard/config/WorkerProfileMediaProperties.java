package dev.buhanzaz.rwms.taskboard.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Private media-service origin used to establish an authenticated worker avatar scope. */
@Validated
@ConfigurationProperties("rwms.media")
public record WorkerProfileMediaProperties(@NotNull URI baseUrl) {}
