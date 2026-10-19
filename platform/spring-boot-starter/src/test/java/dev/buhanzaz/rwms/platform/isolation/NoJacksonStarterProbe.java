package dev.buhanzaz.rwms.platform.isolation;

import java.time.Clock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;

public final class NoJacksonStarterProbe {

    private NoJacksonStarterProbe() {}

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(ProbeApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF")
                .run()) {
            Clock clock = context.getBean(Clock.class);
            if (!"Z".equals(clock.getZone().getId())) {
                throw new IllegalStateException("Starter Clock is not UTC: " + clock.getZone());
            }
            System.out.println("NO_JACKSON_STARTER_OK");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ConfigurationPropertiesScan
    static class ProbeApplication {}
}
