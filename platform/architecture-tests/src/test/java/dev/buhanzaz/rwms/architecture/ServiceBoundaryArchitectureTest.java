package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;
import dev.buhanzaz.rwms.inventory.fixture.SafeConstructorInjectedComponent;
import dev.buhanzaz.rwms.inventory.fixture.UnsafeInventoryDependency;
import dev.buhanzaz.rwms.logistics.fixture.UnsafeLogisticsDependency;
import dev.buhanzaz.rwms.platform.contracts.fixture.InventorySessionModel;
import org.junit.jupiter.api.Test;

class ServiceBoundaryArchitectureTest {
  @Test
  void acceptsExplicitConstructorInjection() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                SafeConstructorInjectedComponent.class,
                SafeConstructorInjectedComponent.InventoryReader.class);

    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_FIELD_INJECTION.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_METHOD_INJECTION.check(classes));
  }

  @Test
  void rejectsInventoryDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeInventoryDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.INVENTORY_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes));
  }

  @Test
  void rejectsLogisticsDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeLogisticsDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.LOGISTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes));
  }

  @Test
  void rejectsSharedBusinessModelInTechnicalContracts() {
    var classes = new ClassFileImporter().importClasses(InventorySessionModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.TECHNICAL_CONTRACTS_CONTAIN_NO_BUSINESS_MODELS.check(classes));
  }
}
