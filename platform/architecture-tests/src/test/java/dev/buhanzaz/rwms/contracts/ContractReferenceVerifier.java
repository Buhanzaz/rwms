package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves canonical {@code $ref} values without network access and verifies every local JSON
 * Pointer against the parsed source that owns it.
 */
final class ContractReferenceVerifier {
  private final ContractDocumentRepository repository;

  ContractReferenceVerifier(ContractDocumentRepository repository) {
    this.repository = repository;
  }

  /** Verifies every {@code $ref} recursively declared by the loaded canonical sources. */
  void verifyAll() {
    for (ContractDocument document : repository.documents()) {
      verifyNode(document, document.root(), "$");
    }
  }

  /**
   * Resolves one {@code $ref} relative to its declaring source and returns the exact target node.
   * Remote references are deliberately not approved by the canonical repository.
   */
  ResolvedContractReference resolve(ContractDocument source, String rawReference) {
    if (rawReference == null || rawReference.isBlank()) {
      throw failure(source, "contains a blank $ref");
    }
    int fragmentStart = rawReference.indexOf('#');
    String rawFileReference =
        fragmentStart < 0 ? rawReference : rawReference.substring(0, fragmentStart);
    String rawFragment = fragmentStart < 0 ? "" : rawReference.substring(fragmentStart + 1);

    ContractDocument target =
        rawFileReference.isEmpty()
            ? source
            : repository.document(resolveLocalFile(source, rawFileReference));
    JsonNode targetNode = resolvePointer(source, target, rawReference, rawFragment);
    return new ResolvedContractReference(target, targetNode);
  }

  private void verifyNode(ContractDocument source, JsonNode node, String location) {
    if (node.isObject()) {
      JsonNode reference = node.get("$ref");
      if (reference != null) {
        if (!reference.isTextual()) {
          throw failure(source, location + " contains a non-textual $ref");
        }
        resolve(source, reference.textValue());
      }
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        verifyNode(source, field.getValue(), location + "/" + field.getKey());
      }
      return;
    }
    if (node.isArray()) {
      for (int index = 0; index < node.size(); index++) {
        verifyNode(source, node.get(index), location + "/" + index);
      }
    }
  }

  private Path resolveLocalFile(ContractDocument source, String rawFileReference) {
    URI uri;
    try {
      uri = URI.create(rawFileReference);
    } catch (IllegalArgumentException exception) {
      throw failure(source, "contains an invalid local $ref " + rawFileReference, exception);
    }
    if (uri.isAbsolute() || uri.getRawAuthority() != null || rawFileReference.startsWith("//")) {
      throw failure(source, "contains an unapproved remote $ref " + rawFileReference);
    }
    try {
      return source.path().getParent().resolve(rawFileReference).normalize();
    } catch (InvalidPathException exception) {
      throw failure(source, "contains an invalid local $ref path " + rawFileReference, exception);
    }
  }

  private JsonNode resolvePointer(
      ContractDocument source,
      ContractDocument target,
      String rawReference,
      String rawFragment) {
    String pointerText = decodeFragment(source, rawReference, rawFragment);
    if (!pointerText.isEmpty() && !pointerText.startsWith("/")) {
      throw failure(source, "contains a non-pointer fragment in $ref " + rawReference);
    }
    JsonPointer pointer;
    try {
      pointer = JsonPointer.compile(pointerText);
    } catch (IllegalArgumentException exception) {
      throw failure(source, "contains an invalid JSON Pointer in $ref " + rawReference, exception);
    }
    JsonNode targetNode = target.root().at(pointer);
    if (targetNode.isMissingNode()) {
      throw failure(
          source,
          "$ref " + rawReference + " does not resolve to a node in " + target.path());
    }
    return targetNode;
  }

  private String decodeFragment(
      ContractDocument source, String rawReference, String rawFragment) {
    try {
      return URLDecoder.decode(rawFragment.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      throw failure(source, "contains an invalid encoded fragment in $ref " + rawReference, exception);
    }
  }

  private static IllegalArgumentException failure(ContractDocument source, String message) {
    return new IllegalArgumentException("Canonical contract " + source.path() + " " + message);
  }

  private static IllegalArgumentException failure(
      ContractDocument source, String message, Exception cause) {
    return new IllegalArgumentException("Canonical contract " + source.path() + " " + message, cause);
  }
}

/** Exact document and node selected by one already-contained canonical {@code $ref}. */
record ResolvedContractReference(ContractDocument document, JsonNode target) {}
