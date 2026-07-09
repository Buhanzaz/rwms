package dev.buhanzaz.wmspanel.service;

import io.jmix.core.security.SystemAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "repair.catalog.xlsx-import-runner", havingValue = "true")
public class RepairCatalogWorkbookImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RepairCatalogWorkbookImportRunner.class);

    private final RepairCatalogWorkbookImportService importService;
    private final SystemAuthenticator systemAuthenticator;
    private final ConfigurableApplicationContext applicationContext;
    private final String workbookPath;

    public RepairCatalogWorkbookImportRunner(RepairCatalogWorkbookImportService importService,
                                             SystemAuthenticator systemAuthenticator,
                                             ConfigurableApplicationContext applicationContext,
                                             @Value("${repair.catalog.xlsx-import-path}") String workbookPath) {
        this.importService = importService;
        this.systemAuthenticator = systemAuthenticator;
        this.applicationContext = applicationContext;
        this.workbookPath = workbookPath;
    }

    @Override
    public void run(ApplicationArguments args) {
        systemAuthenticator.begin("admin");
        try {
            RepairCatalogWorkbookImportService.ImportResult result = importService.importWorkbook(Path.of(workbookPath));
            log.info("Repair catalog workbook import completed: categories created={}, items created={}, items updated={}",
                    result.createdCategories(), result.createdItems(), result.updatedItems());
        } finally {
            systemAuthenticator.end();
        }
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(exitCode);
    }
}
