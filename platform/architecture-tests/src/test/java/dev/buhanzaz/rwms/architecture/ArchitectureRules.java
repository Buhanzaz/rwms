package dev.buhanzaz.rwms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mapper;

final class ArchitectureRules {
  private static final Pattern SENSITIVE_OR_TECHNICAL_TYPE =
      Pattern.compile(
          ".*(Password|Secret|AccessToken|RefreshToken|IdToken|PrivateKey|SigningKey|CredentialMaterial|Outbox|Checksum).*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DOMAIN_TRANSITION_RETURN =
      Pattern.compile(".*(Aggregate|Command|Request)$");
  private static final Pattern BUSINESS_MODEL_TYPE =
      Pattern.compile(
          ".*(Asset|Cabin|Catalog|Company|Contract|Dossier|Equipment|Estimate|Finding|Hold|Inventory|Lease|Logistics|Maintenance|Rental|Repair|Reservation|Shipment|Stock|Task|Transfer|Warehouse|Worker|WriteOff).*",
          Pattern.CASE_INSENSITIVE);
  private static final String[] SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_INVENTORY_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_LOGISTICS_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.media.."
  };

  static final ArchRule TECHNICAL_CONTRACTS_ARE_FRAMEWORK_NEUTRAL =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.platform.contracts..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "lombok..",
              "org.mapstruct..",
              "org.apache.kafka..",
              "org.springframework.kafka..")
          .because("technical contracts must remain immutable and framework-neutral");

  static final ArchRule TECHNICAL_CONTRACTS_DO_NOT_DEPEND_ON_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.platform.contracts..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(SERVICE_PACKAGES)
          .because("cross-service truth comes from versioned schemas, not shared Java models");

  static final ArchRule TECHNICAL_CONTRACTS_CONTAIN_NO_BUSINESS_MODELS =
      classes()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.platform.contracts..")
          .should(describeOnlyTechnicalContractTypes())
          .because("technical-contracts must not become a shared business-domain module");

  static final ArchRule INVENTORY_DOES_NOT_DEPEND_ON_OTHER_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.inventory..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_INVENTORY_SERVICE_PACKAGES)
          .because("inventory owns its models and integrates only through versioned transport contracts")
          .allowEmptyShould(true);

  static final ArchRule LOGISTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.logistics..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_LOGISTICS_SERVICE_PACKAGES)
          .because("logistics owns its models and integrates only through versioned transport contracts")
          .allowEmptyShould(true);

  static final ArchRule SERVICES_DO_NOT_USE_FIELD_INJECTION =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(SERVICE_PACKAGES)
          .should()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
          .because("service collaborators use explicit constructor injection")
          .allowEmptyShould(true);

  static final ArchRule SERVICES_DO_NOT_USE_METHOD_INJECTION =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage(SERVICE_PACKAGES)
          .should()
          .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
          .because("service collaborators use explicit constructor injection")
          .allowEmptyShould(true);

  static final ArchRule MAPPERS_LIVE_IN_MAPPING_PACKAGES =
      classes()
          .that()
          .areAnnotatedWith(Mapper.class)
          .should()
          .resideInAnyPackage("..mapper..", "..mapping..")
          .because("MapStruct is an explicit read/integration boundary")
          .allowEmptyShould(true);

  static final ArchRule MAPPERS_DO_NOT_MUTATE_ENTITIES_FROM_COMMANDS =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(Mapper.class)
          .should(notUseJpaEntitiesAsMappingTargets())
          .because("domain transitions and optimistic state remain explicit service code")
          .allowEmptyShould(true);

  static final ArchRule MAPPERS_STAY_INSIDE_READ_AND_SANITIZED_PAYLOAD_BOUNDARIES =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .areAnnotatedWith(Mapper.class)
          .should(stayInsideReadAndSanitizedPayloadBoundaries())
          .because(
              "MapStruct must not own security, secrets, optimistic mutation, outbox/checksum construction or domain transitions")
          .allowEmptyShould(true);

  private ArchitectureRules() {}

  private static ArchCondition<JavaMethod> notUseJpaEntitiesAsMappingTargets() {
    return new ArchCondition<>("not use a JPA entity as a MapStruct mapping target") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        var returnsEntity = method.getRawReturnType().isAnnotatedWith(Entity.class);
        var mutatesEntityParameter =
            method.getParameters().stream()
                .anyMatch(
                    parameter ->
                        parameter.getRawType().isAnnotatedWith(Entity.class)
                            && parameter.isAnnotatedWith(MappingTarget.class));

        if (returnsEntity || mutatesEntityParameter) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  "%s uses a JPA entity as a MapStruct mapping target"
                      .formatted(method.getFullName())));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> describeOnlyTechnicalContractTypes() {
    return new ArchCondition<>("describe only framework-neutral technical records") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (BUSINESS_MODEL_TYPE.matcher(type.getSimpleName()).matches()) {
          events.add(
              SimpleConditionEvent.violated(
                  type,
                  "%s exposes a business model from technical-contracts"
                      .formatted(type.getName())));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> stayInsideReadAndSanitizedPayloadBoundaries() {
    return new ArchCondition<>("stay inside read DTO and sanitized integration-payload boundaries") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        var hasMappingTarget =
            method.getParameters().stream()
                .anyMatch(parameter -> parameter.isAnnotatedWith(MappingTarget.class));
        var boundaryTypes = new ArrayList<com.tngtech.archunit.core.domain.JavaClass>();
        boundaryTypes.add(method.getRawReturnType());
        method.getParameters().forEach(parameter -> boundaryTypes.add(parameter.getRawType()));
        var touchesSensitiveOrTechnicalType =
            boundaryTypes.stream()
                .anyMatch(
                    type ->
                        SENSITIVE_OR_TECHNICAL_TYPE.matcher(type.getSimpleName()).matches()
                            || containsForbiddenBoundaryPackage(type.getPackageName()));
        var returnType = method.getRawReturnType();
        var createsDomainState =
            returnType.isAnnotatedWith(Entity.class)
                || DOMAIN_TRANSITION_RETURN.matcher(returnType.getSimpleName()).matches()
                || containsDomainStatePackage(returnType.getPackageName());

        if (hasMappingTarget || touchesSensitiveOrTechnicalType || createsDomainState) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  "%s crosses a forbidden MapStruct boundary".formatted(method.getFullName())));
        }
      }
    };
  }

  private static boolean containsForbiddenBoundaryPackage(String packageName) {
    return packageName.contains(".secret.")
        || packageName.contains(".secrets.")
        || packageName.contains(".outbox.")
        || packageName.contains(".checksum.");
  }

  private static boolean containsDomainStatePackage(String packageName) {
    return packageName.contains(".aggregate.")
        || packageName.contains(".command.")
        || packageName.contains(".persistence.entity.");
  }
}
