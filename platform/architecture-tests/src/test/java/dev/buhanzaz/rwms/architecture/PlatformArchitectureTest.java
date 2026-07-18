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
    ArchitectureRules.TECHNICAL_CONTRACTS_DO_NOT_DEPEND_ON_SERVICES.check(classes);
    ArchitectureRules.TECHNICAL_CONTRACTS_CONTAIN_NO_BUSINESS_MODELS.check(classes);
  }

  @Test
  void productionMappersStayInsideTheApprovedBoundary() {
    var classes =
        importer.importPackages(
            "dev.buhanzaz.rwms.asset",
            "dev.buhanzaz.rwms.auth",
            "dev.buhanzaz.rwms.taskboard",
            "dev.buhanzaz.rwms.warehouse",
            "dev.buhanzaz.rwms.maintenance",
            "dev.buhanzaz.rwms.inventory",
            "dev.buhanzaz.rwms.logistics",
            "dev.buhanzaz.rwms.dossier");

    ArchitectureRules.MAPPERS_LIVE_IN_MAPPING_PACKAGES.check(classes);
    ArchitectureRules.MAPPERS_DO_NOT_MUTATE_ENTITIES_FROM_COMMANDS.check(classes);
    ArchitectureRules.MAPPERS_STAY_INSIDE_READ_AND_SANITIZED_PAYLOAD_BOUNDARIES.check(classes);
  }

  @Test
  void servicesUseConstructorInjectionAndOwnTheirModels() {
    var classes =
        importer.importPackages(
            "dev.buhanzaz.rwms.auth",
            "dev.buhanzaz.rwms.taskboard",
            "dev.buhanzaz.rwms.warehouse",
            "dev.buhanzaz.rwms.asset",
            "dev.buhanzaz.rwms.maintenance",
            "dev.buhanzaz.rwms.inventory",
            "dev.buhanzaz.rwms.logistics",
            "dev.buhanzaz.rwms.dossier");

    ArchitectureRules.SERVICES_DO_NOT_USE_FIELD_INJECTION.check(classes);
    ArchitectureRules.SERVICES_DO_NOT_USE_METHOD_INJECTION.check(classes);
    ArchitectureRules.INVENTORY_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes);
    ArchitectureRules.LOGISTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes);
    ArchitectureRules.DOSSIER_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes);
  }
}
