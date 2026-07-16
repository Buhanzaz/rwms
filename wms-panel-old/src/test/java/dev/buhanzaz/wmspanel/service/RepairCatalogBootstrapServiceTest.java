package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.WmsPanelApplication;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.boot.ApplicationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepairCatalogBootstrapServiceTest {

    @Test
    void applicationReadyDoesNotBootstrapLegacyPackagedCatalog() {
        try (ConfigurableApplicationContext context = startContext(true)) {
            assertThat(loadNodes(context)).isEmpty();
            assertThat(loadLinks(context)).isEmpty();
        }
    }

    private ConfigurableApplicationContext startContext(boolean resetCatalogOnStartup) {
        return new SpringApplicationBuilder(WmsPanelApplication.class, ResetCatalogOnStartupConfiguration.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--repair.catalog.test.reset-on-startup=" + resetCatalogOnStartup
                );
    }

    private List<RepairEstimateCatalogNode> loadNodes(ConfigurableApplicationContext context) {
        return withSystemAuth(context, () -> context.getBean(DataManager.class)
                .load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e order by e.code")
                .list());
    }

    private List<RepairEstimateCatalogLink> loadLinks(ConfigurableApplicationContext context) {
        return withSystemAuth(context, () -> context.getBean(DataManager.class)
                .load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        left join fetch e.sourceNode
                        left join fetch e.targetNode
                        order by e.sortOrder, e.id
                        """)
                .list());
    }

    private <T> T withSystemAuth(ConfigurableApplicationContext context, java.util.function.Supplier<T> supplier) {
        SystemAuthenticator authenticator = context.getBean(SystemAuthenticator.class);
        authenticator.begin("admin");
        try {
            return supplier.get();
        } finally {
            authenticator.end();
        }
    }

    private void withSystemAuth(ConfigurableApplicationContext context, Runnable action) {
        SystemAuthenticator authenticator = context.getBean(SystemAuthenticator.class);
        authenticator.begin("admin");
        try {
            action.run();
        } finally {
            authenticator.end();
        }
    }

    @Configuration
    static class ResetCatalogOnStartupConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "repair.catalog.test", name = "reset-on-startup", havingValue = "true")
        ApplicationRunner resetRepairCatalogOnStartup(JdbcTemplate jdbcTemplate) {
            return args -> {
                jdbcTemplate.update("delete from REPAIR_ESTIMATE_TASK_PLAN");
                jdbcTemplate.update("delete from REPAIR_ESTIMATE_CATALOG_LINK");
                jdbcTemplate.update("delete from REPAIR_ESTIMATE_CATALOG_NODE");
            };
        }
    }
}
