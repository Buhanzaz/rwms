package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed machine-readable canonical contract together with its real path inside the bounded
 * {@code contracts/} directory.
 */
record ContractDocument(Path path, JsonNode root) {

  ContractDocument {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(root, "root must not be null");
    if (!root.isObject()) {
      throw new IllegalArgumentException("Canonical contract " + path + " must have an object root");
    }
  }

  boolean isOpenApi() {
    return root.has("openapi");
  }

  boolean isEventCatalog() {
    return root.has("asyncapi");
  }

  boolean isJsonSchema() {
    return root.has("$schema");
  }
}
