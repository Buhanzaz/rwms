package dev.buhanzaz.rwms.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpaLombokSourcePolicyTest {
  @TempDir Path temporaryDirectory;

  @Test
  void currentJpaSourcesFollowLombokSafetyPolicy() {
    var root = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(
        () -> JpaLombokSourcePolicy.assertSafe(root.resolve("services/auth-service/src/main/java")));
    assertDoesNotThrow(
        () ->
            JpaLombokSourcePolicy.assertSafe(
                root.resolve("services/task-board-service/src/main/java")));
    assertDoesNotThrow(
        () ->
            JpaLombokSourcePolicy.assertSafe(
                root.resolve("services/asset-service/src/main/java")));
    assertDoesNotThrow(
        () ->
            JpaLombokSourcePolicy.assertSafe(
                root.resolve("services/warehouse-service/src/main/java")));
    assertDoesNotThrow(
        () ->
            JpaLombokSourcePolicy.assertSafe(
                root.resolve("services/maintenance-service/src/main/java")));
    var inventorySources = root.resolve("services/inventory-service/src/main/java");
    if (Files.isDirectory(inventorySources)) {
      assertDoesNotThrow(() -> JpaLombokSourcePolicy.assertSafe(inventorySources));
    }
  }

  @Test
  void unsafeJpaFixtureProvesTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeEntity.java"),
        """
        import jakarta.persistence.Entity;
        import lombok.Data;

        @Data
        @Entity
        class UnsafeEntity {}
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void protectedFieldSetterFixtureProvesTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeVersionedEntity.java"),
        """
        import jakarta.persistence.Entity;
        import lombok.Setter;

        @Entity
        class UnsafeVersionedEntity {
          @Setter
          private long version;
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void addressableSetterOnOrdinaryFieldIsAllowed() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("SafeDescriptiveEntity.java"),
        """
        import jakarta.persistence.Entity;
        import lombok.Setter;

        @Entity
        class SafeDescriptiveEntity {
          @Setter
          private String description;
        }
        """);

    assertDoesNotThrow(() -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void embeddedIdentitySetterProvesTheAnnotationRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeEmbeddedIdentityEntity.java"),
        """
        import jakarta.persistence.EmbeddedId;
        import jakarta.persistence.Entity;
        import lombok.Setter;

        @Entity
        class UnsafeEmbeddedIdentityEntity {
          @EmbeddedId
          @Setter
          private Object key;
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void protectedMethodAnnotationDoesNotLeakIntoOrdinaryFieldSetter() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("SafeAfterAuditedMethodEntity.java"),
        """
        import jakarta.persistence.Entity;
        import lombok.Setter;

        @Entity
        class SafeAfterAuditedMethodEntity {
          @org.springframework.data.annotation.CreatedDate
          java.time.Instant createdAt() {
            return null;
          }

          @Setter
          private String description;
        }
        """);

    assertDoesNotThrow(() -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void fullyQualifiedValueAndSuperBuilderFixturesProveTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeValueEntity.java"),
        """
        @jakarta.persistence.Entity
        @lombok.Value
        class UnsafeValueEntity {}
        """);
    Files.writeString(
        temporaryDirectory.resolve("UnsafeBuilderEntity.java"),
        """
        @jakarta.persistence.Entity
        @lombok.experimental.SuperBuilder
        class UnsafeBuilderEntity {}
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void fullyQualifiedProtectedFieldSetterProvesTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeTimestampEntity.java"),
        """
        @jakarta.persistence.Entity
        class UnsafeTimestampEntity {
          @lombok.Setter
          private java.time.Instant updatedAt;
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void constructorBuilderInsideEntityBodyProvesTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeConstructorBuilderEntity.java"),
        """
        import jakarta.persistence.Entity;
        import lombok.Builder;

        @Entity
        class UnsafeConstructorBuilderEntity {
          @Builder
          UnsafeConstructorBuilderEntity(String status) {}
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void methodBuilderInsideEntityBodyProvesTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeMethodBuilderEntity.java"),
        """
        @jakarta.persistence.Entity
        class UnsafeMethodBuilderEntity {
          @lombok.Builder
          static UnsafeMethodBuilderEntity restore(String status) {
            return new UnsafeMethodBuilderEntity();
          }
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }

  @Test
  void annotationBasedIdentityVersionTimestampAndInvariantSettersProveTheRule() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("UnsafeAnnotatedEntity.java"),
        """
        import jakarta.persistence.Entity;
        import jakarta.persistence.Id;
        import jakarta.persistence.Version;
        import java.time.Instant;
        import lombok.Setter;

        @Entity
        class UnsafeAnnotatedEntity {
          @Id @Setter
          private String primaryKey;

          @Version @Setter
          private long revision;

          @Setter
          private Instant processedOn;

          @Setter
          private String status;
        }
        """);

    assertThrows(
        AssertionError.class, () -> JpaLombokSourcePolicy.assertSafe(temporaryDirectory));
  }
}
