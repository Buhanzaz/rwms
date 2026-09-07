package dev.buhanzaz.rwms.asset.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Disabled-by-default administrative entry point. Reads only an explicitly configured local
 * manifest after operators have backed up and frozen the approved inventory; never scans live data
 * for targets and exposes no HTTP capability.
 */
@Component
@ConditionalOnProperty(
    prefix = "rwms.asset.inventory-source-isolation",
    name = "enabled",
    havingValue = "true")
final class InventorySourceIsolationRepairRunner implements ApplicationRunner {
  private static final Logger log =
      LoggerFactory.getLogger(InventorySourceIsolationRepairRunner.class);
  private final InventorySourceIsolationRepairService service;
  private final ObjectMapper mapper;
  private final Path manifest;

  InventorySourceIsolationRepairRunner(
      InventorySourceIsolationRepairService service,
      ObjectMapper mapper,
      @Value("${rwms.asset.inventory-source-isolation.manifest}") String manifest) {
    this.service = service;
    this.mapper = mapper;
    this.manifest = Path.of(manifest);
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    if (!manifest.isAbsolute() || !Files.isRegularFile(manifest) || Files.size(manifest) > 131072) {
      throw new IllegalArgumentException("Repair requires an absolute bounded local manifest file");
    }
    var request =
        mapper.readValue(
            Files.readString(manifest), InventorySourceIsolationRepairService.Manifest.class);
    boolean applied = service.isolate(request);
    log.info(
        "Inventory source isolation repair {} for inventory {}",
        applied ? "applied" : "already recorded",
        request.inventoryId());
  }
}
