package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enforces nonblank, contract-local unique operation identifiers for every HTTP operation in a
 * canonical OpenAPI source.
 */
final class OpenApiContractVerifier {
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /** Verifies operation identifiers in each loaded canonical OpenAPI document. */
  void verify(ContractDocumentRepository repository) {
    for (ContractDocument document : repository.documents()) {
      if (document.isOpenApi()) {
        verifyDocument(document);
      }
    }
  }

  private static void verifyDocument(ContractDocument document) {
    JsonNode paths = requiredObject(document, document.root().get("paths"), "paths");
    var operationIds = new LinkedHashMap<String, Path>();
    for (Map.Entry<String, JsonNode> pathEntry : paths.properties()) {
      verifyPathItem(document, pathEntry.getKey(), pathEntry.getValue(), operationIds);
    }
  }

  private static void verifyPathItem(
      ContractDocument document,
      String path,
      JsonNode pathItem,
      Map<String, Path> operationIds) {
    if (!pathItem.isObject()) {
      throw failure(document, "path " + path + " must be an object");
    }
    for (Map.Entry<String, JsonNode> operation : pathItem.properties()) {
      String method = operation.getKey().toLowerCase(Locale.ROOT);
      if (!HTTP_METHODS.contains(method)) {
        continue;
      }
      JsonNode definition = requiredObject(document, operation.getValue(), "operation " + method + " " + path);
      JsonNode operationId = definition.get("operationId");
      if (operationId == null || !operationId.isTextual() || operationId.textValue().isBlank()) {
        throw failure(document, "operation " + method + " " + path + " requires a nonblank operationId");
      }
      Path previous = operationIds.putIfAbsent(operationId.textValue(), document.path());
      if (previous != null) {
        throw failure(
            document,
            "operationId "
                + operationId.textValue()
                + " is duplicated in its owning OpenAPI contract");
      }
    }
  }

  private static JsonNode requiredObject(ContractDocument document, JsonNode value, String location) {
    if (value == null || !value.isObject()) {
      throw failure(document, location + " must be an object");
    }
    return value;
  }

  private static IllegalArgumentException failure(ContractDocument document, String message) {
    return new IllegalArgumentException("Canonical OpenAPI contract " + document.path() + " " + message);
  }
}
