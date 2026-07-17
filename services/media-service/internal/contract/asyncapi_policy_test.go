package contract

import (
	"path/filepath"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

func TestAsyncAPITopicSchemaAndConditionalRecordKeyPolicy(t *testing.T) {
	events := eventsDirectory(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(events, "media-events.yaml")), &document); err != nil {
		t.Fatalf("decode media-events.yaml: %v", err)
	}

	channels := objectAt(t, document, "channels")
	messages := objectAt(t, objectAt(t, document, "components"), "messages")
	tests := []struct {
		name           string
		channel        string
		channelMessage string
		message        string
		address        string
		payloadRef     string
		recordKey      string
	}{
		{
			name: "media facts", channel: "mediaFacts", channelMessage: "mediaFactV1",
			message: "MediaFactV1", address: "rwms.media.media.v1",
			payloadRef: "./media/media-facts-v1.schema.json", recordKey: "aggregateId",
		},
		{
			name: "processing requests", channel: "processingRequests", channelMessage: "processingRequestV1",
			message: "MediaProcessingRequestV1", address: "rwms.media.processing.v1",
			payloadRef: "./media/media-processing-requests-v1.schema.json", recordKey: "aggregateId",
		},
		{
			name: "processing DLT", channel: "processingDeadLetters", channelMessage: "mediaProcessingDltV1",
			message: "MediaProcessingDltV1", address: "rwms.media.processing.v1.media-service-processing-v1.dlt",
			payloadRef: "./media/media-processing-dlt-v1.schema.json",
			recordKey:  "source processingJobId when valid; UUIDv5(OID namespace, SHA-256(raw source bytes)) when invalid",
		},
		{
			name: "inventory owner input", channel: "inventoryOwnerFacts", channelMessage: "inventoryFindingFactV1",
			message: "InventoryFindingFactV1", address: "rwms.inventory.session.v1",
			payloadRef: "./inventory/inventory-events-v1.schema.json", recordKey: "aggregateId",
		},
		{
			name: "inventory owner DLT", channel: "inventoryOwnerDeadLetters", channelMessage: "inventoryOwnerDltV1",
			message: "InventoryOwnerDltV1", address: "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt",
			payloadRef: "./media/media-inventory-owner-dlt-v1.schema.json",
			recordKey:  "source finding aggregateId when identity is valid; UUIDv5(OID namespace, SHA-256(raw source bytes)) when invalid",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			channel := objectAt(t, channels, test.channel)
			if got := stringAt(t, channel, "address"); got != test.address {
				t.Fatalf("channel address = %q, want %q", got, test.address)
			}
			channelMessages := objectAt(t, channel, "messages")
			if got := stringAt(t, objectAt(t, channelMessages, test.channelMessage), "$ref"); got != "#/components/messages/"+test.message {
				t.Fatalf("channel message ref = %q", got)
			}
			message := objectAt(t, messages, test.message)
			payload := objectAt(t, message, "payload")
			if test.message == "InventoryFindingFactV1" {
				assertInventoryOwnerInputNarrowing(t, payload, test.payloadRef)
			} else if got := stringAt(t, payload, "$ref"); got != test.payloadRef {
				t.Fatalf("payload ref = %q, want %q", got, test.payloadRef)
			}
			if got := stringAt(t, message, "x-rwms-record-key"); got != test.recordKey {
				t.Fatalf("record-key rule = %q, want %q", got, test.recordKey)
			}
		})
	}

	assertOperationChannelRef(t, document, "publishMediaFact", "mediaFacts")
	assertOperationChannelRef(t, document, "publishProcessingRequest", "processingRequests")
	assertOperationChannelRef(t, document, "consumeProcessingRequest", "processingRequests")
	assertOperationChannelRef(t, document, "publishProcessingDeadLetter", "processingDeadLetters")
	assertOperationChannelRef(t, document, "consumeInventoryOwnerFact", "inventoryOwnerFacts")
	assertOperationChannelRef(t, document, "publishInventoryOwnerDeadLetter", "inventoryOwnerDeadLetters")

	factSchema := decodeJSONContract(t, readContract(t, filepath.Join(events, "media", "media-facts-v1.schema.json")))
	requestSchema := decodeJSONContract(t, readContract(t, filepath.Join(events, "media", "media-processing-requests-v1.schema.json")))
	dltSchema := decodeJSONContract(t, readContract(t, filepath.Join(events, "media", "media-processing-dlt-v1.schema.json")))
	ownerDltSchema := decodeJSONContract(t, readContract(t, filepath.Join(events, "media", "media-inventory-owner-dlt-v1.schema.json")))
	assertSchemaTopic(t, factSchema, "rwms.media.media.v1")
	assertSchemaTopic(t, requestSchema, "rwms.media.processing.v1")
	assertSchemaTopic(t, dltSchema, "rwms.media.processing.v1.media-service-processing-v1.dlt")
	assertSchemaTopic(t, ownerDltSchema, "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt")
	if got := stringAt(t, factSchema, "x-rwms-record-key"); got != "aggregateId" {
		t.Fatalf("fact schema record key = %q", got)
	}
	if got := stringAt(t, requestSchema, "x-rwms-record-key"); got != "aggregateId" {
		t.Fatalf("request schema record key = %q", got)
	}
	if got := stringAt(t, dltSchema, "x-rwms-record-key"); got != "source processingJobId when valid; UUIDv5(OID namespace, SHA-256(raw source bytes)) when invalid" {
		t.Fatalf("DLT schema record-key rule = %q", got)
	}
	ordering := stringAt(t, requestSchema, "x-rwms-ordering")
	for _, required := range []string{"PROCESSING_JOB", "version 1", "1s/2s/4s", "Validation failures are not retried"} {
		if !strings.Contains(ordering, required) {
			t.Errorf("processing ordering rule %q does not contain %q", ordering, required)
		}
	}
}

func assertInventoryOwnerInputNarrowing(t *testing.T, payload map[string]any, expectedRef string) {
	t.Helper()
	allOf, ok := payload["allOf"].([]any)
	if !ok || len(allOf) != 2 {
		t.Fatalf("inventory owner payload allOf = %#v", payload["allOf"])
	}
	reference, ok := allOf[0].(map[string]any)
	if !ok || stringAt(t, reference, "$ref") != expectedRef {
		t.Fatalf("inventory owner payload base ref = %#v", allOf[0])
	}
	narrowing, ok := allOf[1].(map[string]any)
	if !ok {
		t.Fatalf("inventory owner payload narrowing = %#v", allOf[1])
	}
	properties := objectAt(t, narrowing, "properties")
	if got := stringAt(t, objectAt(t, properties, "aggregateType"), "const"); got != "FINDING" {
		t.Fatalf("inventory owner aggregate type = %q", got)
	}
	events, ok := objectAt(t, properties, "eventType")["enum"].([]any)
	if !ok || len(events) != 3 {
		t.Fatalf("inventory owner event enum = %#v", objectAt(t, properties, "eventType")["enum"])
	}
	allowed := map[string]bool{}
	for _, value := range events {
		text, ok := value.(string)
		if !ok {
			t.Fatalf("inventory owner event enum member = %#v", value)
		}
		allowed[text] = true
	}
	for _, eventType := range []string{"inventory.finding.added.v1", "inventory.finding.inspection-saved.v1", "inventory.finding.owner-proof.v1"} {
		if !allowed[eventType] {
			t.Fatalf("inventory owner event enum misses %s", eventType)
		}
	}
	for _, forbidden := range []string{"inventory.session.started.v1", "inventory.publication.requested.v1"} {
		if allowed[forbidden] {
			t.Fatalf("inventory owner event enum exposes %s", forbidden)
		}
	}
}

func assertOperationChannelRef(t *testing.T, document map[string]any, operation, channel string) {
	t.Helper()
	operations := objectAt(t, document, "operations")
	operationDocument := objectAt(t, operations, operation)
	if got := stringAt(t, objectAt(t, operationDocument, "channel"), "$ref"); got != "#/channels/"+channel {
		t.Fatalf("operation %s channel ref = %q, want #/channels/%s", operation, got, channel)
	}
}

func assertSchemaTopic(t *testing.T, schema map[string]any, expected string) {
	t.Helper()
	if got := stringAt(t, schema, "x-rwms-topic"); got != expected {
		t.Fatalf("schema topic = %q, want %q", got, expected)
	}
}
