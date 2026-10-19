package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.buhanzaz.rwms.analytics.fixture.SafeAnalyticsReadController;
import dev.buhanzaz.rwms.analytics.fixture.UnsafeAnalyticsServiceDependency;
import dev.buhanzaz.rwms.analytics.fixture.UnsafeAnalyticsWriteControllers;
import dev.buhanzaz.rwms.assistant.fixture.SafeAssistantOwnedComponent;
import dev.buhanzaz.rwms.assistant.fixture.UnsafeAssistantInjectionComponents;
import dev.buhanzaz.rwms.assistant.fixture.UnsafeAssistantMapper;
import dev.buhanzaz.rwms.assistant.fixture.UnsafeAssistantServiceDependency;
import dev.buhanzaz.rwms.asset.domain.fixture.SharedAssetModel;
import dev.buhanzaz.rwms.dossier.fixture.UnsafeDossierDependency;
import dev.buhanzaz.rwms.gateway.fixture.SafeGatewayComponent;
import dev.buhanzaz.rwms.gateway.fixture.UnsafeGatewayInfrastructure;
import dev.buhanzaz.rwms.gateway.fixture.UnsafeGatewayServiceDependency;
import dev.buhanzaz.rwms.gateway.fixture.persistence.UnsafeGatewayState;
import dev.buhanzaz.rwms.inventory.fixture.SafeConstructorInjectedComponent;
import dev.buhanzaz.rwms.inventory.fixture.UnsafeInventoryDependency;
import dev.buhanzaz.rwms.logistics.fixture.UnsafeLogisticsDependency;
import dev.buhanzaz.rwms.platform.contracts.fixture.InventorySessionModel;
import org.junit.jupiter.api.Test;

/** Proves each central service-boundary rule against explicit safe and unsafe fixture graphs. */
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
  void rejectsDossierDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeDossierDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.DOSSIER_DOES_NOT_DEPEND_ON_OTHER_SERVICES.check(classes));
  }

  @Test
  void acceptsAssistantOwnedModelWithConstructorInjection() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                SafeAssistantOwnedComponent.class,
                SafeAssistantOwnedComponent.AssistantModel.class);

    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_FIELD_INJECTION.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_METHOD_INJECTION.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.ASSISTANT_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS.check(classes));
  }

  @Test
  void rejectsAssistantFieldInjection() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                UnsafeAssistantInjectionComponents.FieldInjectedComponent.class,
                UnsafeAssistantInjectionComponents.Collaborator.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_FIELD_INJECTION.check(classes));
  }

  @Test
  void rejectsAssistantMethodInjection() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                UnsafeAssistantInjectionComponents.MethodInjectedComponent.class,
                UnsafeAssistantInjectionComponents.Collaborator.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_METHOD_INJECTION.check(classes));
  }

  @Test
  void rejectsAssistantDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeAssistantServiceDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () ->
            ArchitectureRules.ASSISTANT_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS.check(classes));
  }

  @Test
  void rejectsAnalyticsDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeAnalyticsServiceDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () ->
            ArchitectureRules.ANALYTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS.check(classes));
  }

  @Test
  void rejectsAssistantMapperOutsideMappingPackage() {
    var classes = new ClassFileImporter().importClasses(UnsafeAssistantMapper.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.MAPPERS_LIVE_IN_MAPPING_PACKAGES.check(classes));
  }

  @Test
  void acceptsStatelessGatewayWithConstructorInjection() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                SafeGatewayComponent.class, SafeGatewayComponent.RouteForwarder.class);

    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_FIELD_INJECTION.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.SERVICES_DO_NOT_USE_METHOD_INJECTION.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.GATEWAY_DOES_NOT_DEPEND_ON_SERVICE_PACKAGES.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.GATEWAY_DOES_NOT_DEPEND_ON_STATEFUL_INFRASTRUCTURE.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.GATEWAY_DECLARES_NO_PERSISTENCE_TYPES.check(classes));
  }

  @Test
  void rejectsGatewayDependencyOnAnotherServiceModel() {
    var classes =
        new ClassFileImporter()
            .importClasses(UnsafeGatewayServiceDependency.class, SharedAssetModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.GATEWAY_DOES_NOT_DEPEND_ON_SERVICE_PACKAGES.check(classes));
  }

  @Test
  void rejectsGatewayJpaEntity() {
    assertGatewayInfrastructureRejected(UnsafeGatewayInfrastructure.GatewayEntity.class);
    assertGatewayPersistenceTypeRejected(UnsafeGatewayInfrastructure.GatewayEntity.class);
  }

  @Test
  void rejectsGatewayDataSource() {
    assertGatewayInfrastructureRejected(UnsafeGatewayInfrastructure.DataSourceClient.class);
  }

  @Test
  void rejectsGatewayJdbcClient() {
    assertGatewayInfrastructureRejected(UnsafeGatewayInfrastructure.JdbcClient.class);
  }

  @Test
  void rejectsGatewaySpringDataRepository() {
    assertGatewayInfrastructureRejected(
        UnsafeGatewayInfrastructure.SpringDataGatewayRepository.class);
    assertGatewayPersistenceTypeRejected(
        UnsafeGatewayInfrastructure.SpringDataGatewayRepository.class);
  }

  @Test
  void rejectsGatewayKafkaClient() {
    assertGatewayInfrastructureRejected(UnsafeGatewayInfrastructure.KafkaClient.class);
  }

  @Test
  void rejectsGatewayLocalRepositoryAbstraction() {
    assertGatewayPersistenceTypeRejected(
        UnsafeGatewayInfrastructure.CustomGatewayRepository.class);
  }

  @Test
  void rejectsGatewayPersistencePackage() {
    assertGatewayPersistenceTypeRejected(UnsafeGatewayState.class);
  }

  @Test
  void acceptsAnalyticsGetMapping() {
    var classes =
        new ClassFileImporter()
            .importClasses(
                SafeAnalyticsReadController.class,
                SafeAnalyticsReadController.AnalyticsReader.class);

    assertDoesNotThrow(
        () -> ArchitectureRules.READ_MODEL_HTTP_BOUNDARIES_ARE_READ_ONLY.check(classes));
    assertDoesNotThrow(
        () -> ArchitectureRules.ANALYTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS.check(classes));
  }

  @Test
  void rejectsAnalyticsPostMapping() {
    assertReadModelWriteMappingRejected(UnsafeAnalyticsWriteControllers.PostController.class);
  }

  @Test
  void rejectsAnalyticsPutMapping() {
    assertReadModelWriteMappingRejected(UnsafeAnalyticsWriteControllers.PutController.class);
  }

  @Test
  void rejectsAnalyticsPatchMapping() {
    assertReadModelWriteMappingRejected(UnsafeAnalyticsWriteControllers.PatchController.class);
  }

  @Test
  void rejectsAnalyticsDeleteMapping() {
    assertReadModelWriteMappingRejected(UnsafeAnalyticsWriteControllers.DeleteController.class);
  }

  @Test
  void rejectsAnalyticsGenericClassLevelPostMapping() {
    assertReadModelWriteMappingRejected(
        UnsafeAnalyticsWriteControllers.GenericPostController.class);
  }

  @Test
  void rejectsAnalyticsBareRequestMapping() {
    assertReadModelWriteMappingRejected(
        UnsafeAnalyticsWriteControllers.BareRequestMappingController.class);
  }

  @Test
  void rejectsSharedBusinessModelInTechnicalContracts() {
    var classes = new ClassFileImporter().importClasses(InventorySessionModel.class);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.TECHNICAL_CONTRACTS_CONTAIN_NO_BUSINESS_MODELS.check(classes));
  }

  private static void assertGatewayInfrastructureRejected(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.GATEWAY_DOES_NOT_DEPEND_ON_STATEFUL_INFRASTRUCTURE.check(classes));
  }

  private static void assertGatewayPersistenceTypeRejected(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.GATEWAY_DECLARES_NO_PERSISTENCE_TYPES.check(classes));
  }

  private static void assertReadModelWriteMappingRejected(Class<?> fixture) {
    var classes = new ClassFileImporter().importClasses(fixture);

    assertThrows(
        AssertionError.class,
        () -> ArchitectureRules.READ_MODEL_HTTP_BOUNDARIES_ARE_READ_ONLY.check(classes));
  }
}
