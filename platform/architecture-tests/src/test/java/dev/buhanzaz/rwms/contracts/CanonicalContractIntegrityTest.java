package dev.buhanzaz.rwms.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executable proof that the checked-in canonical contract boundary is parseable, self-contained
 * and protected against the most likely accidental reference and identifier regressions.
 */
@Tag("canonical-contract-integrity")
class CanonicalContractIntegrityTest {
  private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

  @TempDir Path temporaryDirectory;

  @Test
  void validatesEveryCheckedInCanonicalContract() {
    Path projectRoot = Path.of(System.getProperty("rwms.root.dir"));

    assertDoesNotThrow(
        () -> new CanonicalContractIntegrityGate().verify(projectRoot.resolve("contracts")));
  }

  @Test
  void rejectsMalformedYamlAndJsonFixtures() throws Exception {
    Path yamlRoot = temporaryDirectory.resolve("malformed-yaml");
    write(yamlRoot, "broken.yaml", "openapi: [unterminated");

    IllegalArgumentException yamlFailure =
        assertThrows(IllegalArgumentException.class, () -> ContractDocumentRepository.load(yamlRoot));
    assertTrue(yamlFailure.getMessage().contains("broken.yaml"));

    Path jsonRoot = temporaryDirectory.resolve("malformed-json");
    write(jsonRoot, "broken.json", "{ \"openapi\": ");

    IllegalArgumentException jsonFailure =
        assertThrows(IllegalArgumentException.class, () -> ContractDocumentRepository.load(jsonRoot));
    assertTrue(jsonFailure.getMessage().contains("broken.json"));
  }

  @Test
  void rejectsDuplicateCanonicalSchemaAndCatalogEventIdentifiers() throws Exception {
    Path schemaRoot = temporaryDirectory.resolve("duplicate-schemas");
    write(
        schemaRoot,
        "events/asset/duplicate.schema.json",
        schema("https://rwms.local/contracts/events/asset/duplicate.schema.json"));
    write(
        schemaRoot,
        "events/asset/second.schema.json",
        schema("https://rwms.local/contracts/events/asset/duplicate.schema.json"));

    IllegalArgumentException schemaFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CanonicalSchemaCompiler().verify(ContractDocumentRepository.load(schemaRoot)));
    assertTrue(schemaFailure.getMessage().contains("declared by both"));

    Path catalogRoot = temporaryDirectory.resolve("duplicate-events");
    write(
        catalogRoot,
        "fact.schema.json",
        schema("https://rwms.local/contracts/events/fact.schema.json"));
    write(catalogRoot, "catalog.yaml", catalog("fixture.fact.v1", "fixture.fact.v1", "./fact.schema.json"));
    ContractDocumentRepository repository = ContractDocumentRepository.load(catalogRoot);

    IllegalArgumentException eventFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventCatalogVerifier(new ContractReferenceVerifier(repository)).verify(repository));
    assertTrue(eventFailure.getMessage().contains("duplicated in its owning event catalog"));
  }

  @Test
  void rejectsDuplicateOpenApiOperationIdentifiers() throws Exception {
    Path root = temporaryDirectory.resolve("duplicate-openapi-operation-id");
    write(
        root,
        "duplicate-openapi.yaml",
        """
        openapi: 3.1.0
        info:
          title: Fixture OpenAPI
          version: 1.0.0
        paths:
          /first:
            get:
              operationId: duplicateOperation
              responses:
                '204': { description: No content }
          /second:
            post:
              operationId: duplicateOperation
              responses:
                '204': { description: No content }
        """);
    ContractDocumentRepository repository = ContractDocumentRepository.load(root);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> new OpenApiContractVerifier().verify(repository));
    assertTrue(failure.getMessage().contains("duplicated in its owning OpenAPI contract"));
  }

  @Test
  void rejectsWrongCanonicalSchemaNamespaceOrPathAndInvalidEventName() throws Exception {
    Path wrongHostRoot = temporaryDirectory.resolve("wrong-schema-host");
    write(
        wrongHostRoot,
        "events/asset/fact.schema.json",
        schema("https://rwms.example/contracts/events/asset/fact.schema.json"));

    IllegalArgumentException wrongHostFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CanonicalSchemaCompiler().verify(ContractDocumentRepository.load(wrongHostRoot)));
    assertTrue(wrongHostFailure.getMessage().contains("path-bound canonical identifier"));

    Path wrongPathRoot = temporaryDirectory.resolve("wrong-schema-path");
    write(
        wrongPathRoot,
        "events/asset/fact.schema.json",
        schema("https://rwms.local/contracts/events/asset/other.schema.json"));

    IllegalArgumentException wrongPathFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CanonicalSchemaCompiler().verify(ContractDocumentRepository.load(wrongPathRoot)));
    assertTrue(wrongPathFailure.getMessage().contains("path-bound canonical identifier"));

    Path invalidEventRoot = temporaryDirectory.resolve("invalid-event-name");
    write(
        invalidEventRoot,
        "fact.schema.json",
        schema("https://rwms.local/contracts/events/fact.schema.json"));
    write(
        invalidEventRoot,
        "catalog.yaml",
        catalog("Fixture.Invalid.v1", "fixture.valid.v1", "./fact.schema.json"));
    ContractDocumentRepository invalidEventRepository = ContractDocumentRepository.load(invalidEventRoot);

    IllegalArgumentException invalidEventFailure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new EventCatalogVerifier(new ContractReferenceVerifier(invalidEventRepository))
                    .verify(invalidEventRepository));
    assertTrue(invalidEventFailure.getMessage().contains("canonical lowercase namespaced .vN format"));
  }

  @Test
  void rejectsMissingAndEscapingOrRemoteReferences() throws Exception {
    Path missingRoot = temporaryDirectory.resolve("missing-pointer");
    write(
        missingRoot,
        "missing.schema.json",
        """
        {
          "$schema": "%s",
          "$id": "https://rwms.local/contracts/events/missing.schema.json",
          "$ref": "#/$defs/absent"
        }
        """.formatted(DRAFT_2020_12));
    ContractDocumentRepository missingRepository = ContractDocumentRepository.load(missingRoot);

    IllegalArgumentException missingFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ContractReferenceVerifier(missingRepository).verifyAll());
    assertTrue(missingFailure.getMessage().contains("does not resolve to a node"));

    Path escapingRoot = temporaryDirectory.resolve("escaping/contracts");
    write(
        escapingRoot,
        "escaping.schema.json",
        """
        {
          "$schema": "%s",
          "$id": "https://rwms.local/contracts/events/escaping.schema.json",
          "$ref": "../outside.schema.json"
        }
        """.formatted(DRAFT_2020_12));
    ContractDocumentRepository escapingRepository = ContractDocumentRepository.load(escapingRoot);

    IllegalArgumentException escapingFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ContractReferenceVerifier(escapingRepository).verifyAll());
    assertTrue(escapingFailure.getMessage().contains("escapes"));

    Path remoteRoot = temporaryDirectory.resolve("remote-reference");
    write(
        remoteRoot,
        "remote.schema.json",
        """
        {
          "$schema": "%s",
          "$id": "https://rwms.local/contracts/events/remote.schema.json",
          "$ref": "https://unapproved.example/contract.schema.json"
        }
        """.formatted(DRAFT_2020_12));
    ContractDocumentRepository remoteRepository = ContractDocumentRepository.load(remoteRoot);

    IllegalArgumentException remoteFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ContractReferenceVerifier(remoteRepository).verifyAll());
    assertTrue(remoteFailure.getMessage().contains("unapproved remote"));
  }

  @Test
  void rejectsBrokenCatalogSchemaReferences() throws Exception {
    Path root = temporaryDirectory.resolve("broken-catalog");
    write(root, "catalog.yaml", catalog("fixture.first.v1", "fixture.second.v1", "./missing.schema.json"));
    ContractDocumentRepository repository = ContractDocumentRepository.load(root);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EventCatalogVerifier(new ContractReferenceVerifier(repository)).verify(repository));
    assertTrue(failure.getMessage().contains("does not exist"));
  }

  private static String schema(String id) {
    return """
        {
          "$schema": "%s",
          "$id": "%s",
          "type": "object"
        }
        """.formatted(DRAFT_2020_12, id);
  }

  private static String catalog(String firstEventId, String secondEventId, String schemaReference) {
    return """
        asyncapi: 3.1.0
        info:
          title: Fixture catalog
          version: 1.0.0
        channels:
          fixtureFacts:
            address: rwms.fixture.fact.v1
            messages:
              first: { $ref: '#/components/messages/FirstFact' }
              second: { $ref: '#/components/messages/SecondFact' }
        operations:
          publishFixtureFacts:
            action: send
            channel: { $ref: '#/channels/fixtureFacts' }
        components:
          messages:
            FirstFact:
              name: %s
              payload: { $ref: '%s' }
            SecondFact:
              name: %s
              payload: { $ref: '%s' }
        """.formatted(firstEventId, schemaReference, secondEventId, schemaReference);
  }

  private static void write(Path root, String relativePath, String contents) throws Exception {
    Path target = root.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, contents);
  }
}
