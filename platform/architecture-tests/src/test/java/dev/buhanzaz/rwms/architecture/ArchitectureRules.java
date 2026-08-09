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
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/** Defines the executable Java dependency and framework-boundary policy for active RWMS services. */
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
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.gateway..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_INVENTORY_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.gateway..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_ASSISTANT_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.gateway..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_ANALYTICS_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.gateway..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_GATEWAY_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] GATEWAY_STATEFUL_INFRASTRUCTURE_PACKAGES = {
    "jakarta.persistence..",
    "javax.persistence..",
    "java.sql..",
    "javax.sql..",
    "org.springframework.data..",
    "org.springframework.jdbc..",
    "org.apache.kafka..",
    "org.springframework.kafka.."
  };
  private static final String[] READ_MODEL_PACKAGES = {
    "dev.buhanzaz.rwms.analytics..", "dev.buhanzaz.rwms.dossier.."
  };
  private static final Set<RequestMethod> WRITE_REQUEST_METHODS =
      EnumSet.of(
          RequestMethod.POST,
          RequestMethod.PUT,
          RequestMethod.PATCH,
          RequestMethod.DELETE);
  private static final String[] NON_LOGISTICS_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.dossier..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.gateway..",
    "dev.buhanzaz.rwms.media.."
  };
  private static final String[] NON_DOSSIER_SERVICE_PACKAGES = {
    "dev.buhanzaz.rwms.auth..",
    "dev.buhanzaz.rwms.taskboard..",
    "dev.buhanzaz.rwms.warehouse..",
    "dev.buhanzaz.rwms.asset..",
    "dev.buhanzaz.rwms.maintenance..",
    "dev.buhanzaz.rwms.inventory..",
    "dev.buhanzaz.rwms.logistics..",
    "dev.buhanzaz.rwms.assistant..",
    "dev.buhanzaz.rwms.analytics..",
    "dev.buhanzaz.rwms.gateway..",
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

  static final ArchRule DOSSIER_DOES_NOT_DEPEND_ON_OTHER_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.dossier..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_DOSSIER_SERVICE_PACKAGES)
          .because(
              "dossier owns its projection model and consumes only versioned transport schemas")
          .allowEmptyShould(true);

  static final ArchRule ASSISTANT_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.assistant..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_ASSISTANT_SERVICE_PACKAGES)
          .because(
              "assistant owns conversation state and reaches other owners only through transport boundaries")
          .allowEmptyShould(true);

  static final ArchRule ANALYTICS_DOES_NOT_DEPEND_ON_OTHER_SERVICE_MODELS =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.analytics..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_ANALYTICS_SERVICE_PACKAGES)
          .because(
              "analytics owns only its projection model and consumes versioned transport facts")
          .allowEmptyShould(true);

  static final ArchRule GATEWAY_DOES_NOT_DEPEND_ON_SERVICE_PACKAGES =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.gateway..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(NON_GATEWAY_SERVICE_PACKAGES)
          .because("the gateway is a transport boundary, never a business-service client library")
          .allowEmptyShould(true);

  static final ArchRule GATEWAY_DOES_NOT_DEPEND_ON_STATEFUL_INFRASTRUCTURE =
      noClasses()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.gateway..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(GATEWAY_STATEFUL_INFRASTRUCTURE_PACKAGES)
          .because("the gateway owns no database, repository, JDBC path or Kafka participation")
          .allowEmptyShould(true);

  static final ArchRule GATEWAY_DECLARES_NO_PERSISTENCE_TYPES =
      classes()
          .that()
          .resideInAPackage("dev.buhanzaz.rwms.gateway..")
          .should(new NotGatewayPersistenceTypeCondition())
          .because("the gateway is stateless and declares no persistence abstraction")
          .allowEmptyShould(true);

  static final ArchRule READ_MODEL_HTTP_BOUNDARIES_ARE_READ_ONLY =
      CompositeArchRule.of(
              methods()
                  .that()
                  .areDeclaredInClassesThat()
                  .resideInAnyPackage(READ_MODEL_PACKAGES)
                  .should(new ReadOnlyHttpMethodCondition())
                  .allowEmptyShould(true))
          .and(
              classes()
                  .that()
                  .resideInAnyPackage(READ_MODEL_PACKAGES)
                  .should(new ReadOnlyHttpTypeCondition())
                  .allowEmptyShould(true))
          .because("analytics and dossier projections expose queries and never own commands");

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

  private static boolean hasComposedWriteMapping(JavaMethod method) {
    return method.isAnnotatedWith(PostMapping.class)
        || method.isAnnotatedWith(PutMapping.class)
        || method.isAnnotatedWith(PatchMapping.class)
        || method.isAnnotatedWith(DeleteMapping.class);
  }

  private static boolean hasWriteRequestMapping(JavaMethod method) {
    if (!method.isAnnotatedWith(RequestMapping.class)) return false;
    var requestMethods = method.getAnnotationOfType(RequestMapping.class).method();
    return requestMethods.length == 0
        || Arrays.stream(requestMethods).anyMatch(WRITE_REQUEST_METHODS::contains);
  }

  private static boolean hasWriteRequestMapping(JavaClass type) {
    return type.isAnnotatedWith(RequestMapping.class)
        && Arrays.stream(type.getAnnotationOfType(RequestMapping.class).method())
            .anyMatch(WRITE_REQUEST_METHODS::contains);
  }

  /** Rejects persistence stereotypes and repository-shaped abstractions declared by the gateway. */
  private static final class NotGatewayPersistenceTypeCondition
      extends ArchCondition<JavaClass> {
    private NotGatewayPersistenceTypeCondition() {
      super("not declare a gateway persistence type");
    }

    @Override
    public void check(JavaClass type, ConditionEvents events) {
      var persistenceType =
          type.isAnnotatedWith("jakarta.persistence.Entity")
              || type.isAnnotatedWith("javax.persistence.Entity")
              || type.isAnnotatedWith("org.springframework.stereotype.Repository")
              || type.isMetaAnnotatedWith("org.springframework.stereotype.Repository")
              || type.getSimpleName().endsWith("Repository")
              || containsPackageSegment(type.getPackageName(), "persistence")
              || containsPackageSegment(type.getPackageName(), "repository");
      if (persistenceType) {
        events.add(
            SimpleConditionEvent.violated(
                type, "%s declares forbidden gateway persistence".formatted(type.getName())));
      }
    }
  }

  private static boolean containsPackageSegment(String packageName, String segment) {
    return ("." + packageName + ".").contains("." + segment + ".");
  }

  /** Rejects composed or generic mutating HTTP mappings on a read-model method. */
  private static final class ReadOnlyHttpMethodCondition extends ArchCondition<JavaMethod> {
    private ReadOnlyHttpMethodCondition() {
      super("declare no POST, PUT, PATCH or DELETE mapping");
    }

    @Override
    public void check(JavaMethod method, ConditionEvents events) {
      if (hasComposedWriteMapping(method) || hasWriteRequestMapping(method)) {
        events.add(
            SimpleConditionEvent.violated(
                method, "%s declares a read-model write mapping".formatted(method.getFullName())));
      }
    }
  }

  /** Rejects a mutating generic base mapping on a read-model controller or endpoint interface. */
  private static final class ReadOnlyHttpTypeCondition extends ArchCondition<JavaClass> {
    private ReadOnlyHttpTypeCondition() {
      super("declare no POST, PUT, PATCH or DELETE base mapping");
    }

    @Override
    public void check(JavaClass type, ConditionEvents events) {
      if (hasWriteRequestMapping(type)) {
        events.add(
            SimpleConditionEvent.violated(
                type, "%s declares a read-model write base mapping".formatted(type.getName())));
      }
    }
  }
}
