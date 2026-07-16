package dev.buhanzaz.rwms.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class PlatformArchitectureTest {
  private final ClassFileImporter importer =
      new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);

  @Test
  void technicalContractsRemainFrameworkNeutral() {
    var classes = importer.importPackages("dev.buhanzaz.rwms.platform.contracts");

    ArchitectureRules.TECHNICAL_CONTRACTS_ARE_FRAMEWORK_NEUTRAL.check(classes);
  }

  @Test
  void productionMappersStayInsideTheApprovedBoundary() {
    var classes =
        importer.importPackages(
            "dev.buhanzaz.rwms.asset",
            "dev.buhanzaz.rwms.auth",
            "dev.buhanzaz.rwms.taskboard",
            "dev.buhanzaz.rwms.warehouse");

    ArchitectureRules.MAPPERS_LIVE_IN_MAPPING_PACKAGES.check(classes);
    ArchitectureRules.MAPPERS_DO_NOT_MUTATE_ENTITIES_FROM_COMMANDS.check(classes);
    ArchitectureRules.MAPPERS_STAY_INSIDE_READ_AND_SANITIZED_PAYLOAD_BOUNDARIES.check(classes);
  }
}
