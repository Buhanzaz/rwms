package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the actual AsyncAPI catalog shape used by RWMS: channel and operation links are local,
 * message names are catalog-local event identifiers, and external payload references select
 * canonical JSON Schema documents.
 */
final class EventCatalogVerifier {
  private static final Pattern CANONICAL_EVENT_ID =
      Pattern.compile(
          "^[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+\\.v[1-9][0-9]*$");

  private final ContractReferenceVerifier references;

  EventCatalogVerifier(ContractReferenceVerifier references) {
    this.references = references;
  }

  /** Verifies every loaded canonical AsyncAPI producer or consumer catalog. */
  void verify(ContractDocumentRepository repository) {
    for (ContractDocument document : repository.documents()) {
      if (document.isEventCatalog()) {
        verifyDocument(document);
      }
    }
  }

  private void verifyDocument(ContractDocument document) {
    JsonNode components = requiredObject(document, document.root().get("components"), "components");
    JsonNode messages = requiredObject(document, components.get("messages"), "components.messages");
    verifyMessages(document, messages);
    JsonNode channels = requiredObject(document, document.root().get("channels"), "channels");
    verifyChannels(document, channels);
    JsonNode operations = requiredObject(document, document.root().get("operations"), "operations");
    verifyOperations(document, operations);
  }

  private void verifyMessages(ContractDocument document, JsonNode messages) {
    if (messages.isEmpty()) {
      throw failure(document, "components.messages must not be empty");
    }
    var eventIds = new HashSet<String>();
    for (Map.Entry<String, JsonNode> entry : messages.properties()) {
      if (entry.getKey().isBlank()) {
        throw failure(document, "components.messages contains a blank component key");
      }
      JsonNode message = requiredObject(document, entry.getValue(), "components.messages." + entry.getKey());
      String eventId = requiredText(document, message.get("name"), "message " + entry.getKey() + " name");
      if (!CANONICAL_EVENT_ID.matcher(eventId).matches()) {
        throw failure(
            document,
            "event identifier "
                + eventId
                + " must match the canonical lowercase namespaced .vN format");
      }
      if (!eventIds.add(eventId)) {
        throw failure(
            document,
            "event identifier " + eventId + " is duplicated in its owning event catalog");
      }
      JsonNode payload = message.get("payload");
      if (payload == null || !payload.isObject()) {
        throw failure(document, "message " + entry.getKey() + " requires an object payload schema");
      }
      verifyPayloadReferences(document, payload);
    }
  }

  private void verifyChannels(ContractDocument document, JsonNode channels) {
    if (channels.isEmpty()) {
      throw failure(document, "channels must not be empty");
    }
    for (Map.Entry<String, JsonNode> entry : channels.properties()) {
      JsonNode channel = requiredObject(document, entry.getValue(), "channel " + entry.getKey());
      requiredText(document, channel.get("address"), "channel " + entry.getKey() + " address");
      JsonNode messageReferences = requiredObject(document, channel.get("messages"), "channel " + entry.getKey() + " messages");
      if (messageReferences.isEmpty()) {
        throw failure(document, "channel " + entry.getKey() + " must reference at least one message");
      }
      for (Map.Entry<String, JsonNode> reference : messageReferences.properties()) {
        String ref = requiredReference(document, reference.getValue(), "channel " + entry.getKey());
        requireDirectLocalReference(document, ref, "#/components/messages/", "component message");
        ResolvedContractReference resolved = references.resolve(document, ref);
        if (!resolved.document().path().equals(document.path()) || !resolved.target().isObject()) {
          throw failure(document, "channel " + entry.getKey() + " must resolve to a component message");
        }
      }
    }
  }

  private void verifyOperations(ContractDocument document, JsonNode operations) {
    if (operations.isEmpty()) {
      throw failure(document, "operations must not be empty");
    }
    for (Map.Entry<String, JsonNode> entry : operations.properties()) {
      JsonNode operation = requiredObject(document, entry.getValue(), "operation " + entry.getKey());
      String action = requiredText(document, operation.get("action"), "operation " + entry.getKey() + " action");
      if (!Set.of("send", "receive").contains(action)) {
        throw failure(document, "operation " + entry.getKey() + " must use send or receive action");
      }
      JsonNode channel = requiredObject(document, operation.get("channel"), "operation " + entry.getKey() + " channel");
      String ref = requiredReference(document, channel, "operation " + entry.getKey() + " channel");
      requireDirectLocalReference(document, ref, "#/channels/", "channel");
      ResolvedContractReference resolved = references.resolve(document, ref);
      if (!resolved.document().path().equals(document.path()) || !resolved.target().isObject()) {
        throw failure(document, "operation " + entry.getKey() + " must resolve to a channel");
      }
    }
  }

  private void verifyPayloadReferences(ContractDocument document, JsonNode node) {
    if (node.isObject()) {
      JsonNode reference = node.get("$ref");
      if (reference != null) {
        String ref = requiredText(document, reference, "payload $ref");
        ResolvedContractReference resolved = references.resolve(document, ref);
        if (!ref.startsWith("#") && !resolved.document().isJsonSchema()) {
          throw failure(
              document,
              "payload $ref " + ref + " must target a canonical JSON Schema document");
        }
        if (!ref.startsWith("#")) {
          requiredText(document, resolved.document().root().get("$id"), "referenced schema $id");
        }
      }
      for (JsonNode value : node) {
        verifyPayloadReferences(document, value);
      }
      return;
    }
    if (node.isArray()) {
      for (JsonNode value : node) {
        verifyPayloadReferences(document, value);
      }
    }
  }

  private static void requireDirectLocalReference(
      ContractDocument document, String ref, String prefix, String targetKind) {
    String component = ref.startsWith(prefix) ? ref.substring(prefix.length()) : "";
    if (component.isBlank() || component.contains("/")) {
      throw failure(document, "$ref " + ref + " must directly select one local " + targetKind);
    }
  }

  private static String requiredReference(ContractDocument document, JsonNode node, String location) {
    JsonNode reference = node.get("$ref");
    if (reference == null || !reference.isTextual() || reference.textValue().isBlank()) {
      throw failure(document, location + " requires a nonblank $ref");
    }
    return reference.textValue();
  }

  private static JsonNode requiredObject(ContractDocument document, JsonNode node, String location) {
    if (node == null || !node.isObject()) {
      throw failure(document, location + " must be an object");
    }
    return node;
  }

  private static String requiredText(ContractDocument document, JsonNode node, String location) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw failure(document, location + " must be nonblank text");
    }
    return node.textValue();
  }

  private static IllegalArgumentException failure(ContractDocument document, String message) {
    return new IllegalArgumentException("Canonical event catalog " + document.path() + " " + message);
  }
}
