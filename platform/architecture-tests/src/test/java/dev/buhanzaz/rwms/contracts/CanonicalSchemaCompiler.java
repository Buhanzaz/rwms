package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles each canonical JSON Schema with the exact draft declared by its {@code $schema} URI
 * and protects the global canonical {@code $id} namespace from collisions.
 */
final class CanonicalSchemaCompiler {

  /** Verifies all loaded JSON Schema documents and their canonical identifiers. */
  void verify(ContractDocumentRepository repository) {
    var schemaIds = new LinkedHashMap<String, Path>();
    for (ContractDocument document : repository.documents()) {
      if (document.isJsonSchema()) {
        verifySchema(document, repository, schemaIds);
      }
    }
  }

  private static void verifySchema(
      ContractDocument document,
      ContractDocumentRepository repository,
      Map<String, Path> schemaIds) {
    String schemaId = requiredText(document, "$id");
    Path previous = schemaIds.putIfAbsent(schemaId, document.path());
    if (previous != null) {
      throw new IllegalArgumentException(
          "Canonical JSON Schema $id "
              + schemaId
              + " is declared by both "
              + previous
              + " and "
              + document.path());
    }
    verifyPathBoundIdentifier(document, repository, schemaId);
    String declaredDraft = requiredText(document, "$schema");
    SpecVersion.VersionFlag version =
        SpecVersion.VersionFlag.fromId(declaredDraft)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Canonical JSON Schema "
                            + document.path()
                            + " declares unsupported $schema "
                            + declaredDraft));
    compile(document, version);
  }

  private static void verifyPathBoundIdentifier(
      ContractDocument document, ContractDocumentRepository repository, String schemaId) {
    String relativePath = repository.relativePath(document).toString().replace('\\', '/');
    String expectedId;
    if (relativePath.startsWith("events/technical/")) {
      expectedId = "https://rwms.example/contracts/" + relativePath;
    } else if (relativePath.startsWith("events/")) {
      expectedId = "https://rwms.local/contracts/" + relativePath;
    } else {
      throw new IllegalArgumentException(
          "Canonical JSON Schema "
              + document.path()
              + " must live under contracts/events to have a canonical $id namespace");
    }
    if (!schemaId.equals(expectedId)) {
      throw new IllegalArgumentException(
          "Canonical JSON Schema "
              + document.path()
              + " declares $id "
              + schemaId
              + " but its path-bound canonical identifier is "
              + expectedId);
    }
  }

  private static String requiredText(ContractDocument document, String field) {
    JsonNode value = document.root().get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(
          "Canonical JSON Schema " + document.path() + " requires a nonblank " + field);
    }
    return value.textValue();
  }

  private static void compile(ContractDocument document, SpecVersion.VersionFlag version) {
    try {
      JsonSchemaFactory.getInstance(version).getSchema(document.root());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Canonical JSON Schema "
              + document.path()
              + " cannot compile with declared draft "
              + version.getId(),
          exception);
    }
  }
}
