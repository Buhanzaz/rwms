package contract

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

const legacyUnionSchemaSHA256 = "560fe9f6d5d8f104a405cb51eab655f75be367b03c3c88ca65f9e61074ce53e0"

func TestEventSchemasAreSeparateStrictContracts(t *testing.T) {
	events := eventsDirectory(t)
	factsRaw := readContract(t, filepath.Join(events, "media", "media-facts-v1.schema.json"))
	requestsRaw := readContract(t, filepath.Join(events, "media", "media-processing-requests-v1.schema.json"))
	dltRaw := readContract(t, filepath.Join(events, "media", "media-processing-dlt-v1.schema.json"))
	ownerDltRaw := readContract(t, filepath.Join(events, "media", "media-inventory-owner-dlt-v1.schema.json"))
	facts := decodeJSONContract(t, factsRaw)
	requests := decodeJSONContract(t, requestsRaw)
	dlt := decodeJSONContract(t, dltRaw)
	ownerDlt := decodeJSONContract(t, ownerDltRaw)

	assertFalse(t, facts, "additionalProperties")
	assertFalse(t, requests, "additionalProperties")
	assertFalse(t, dlt, "additionalProperties")
	assertFalse(t, ownerDlt, "additionalProperties")
	factDefinitions := objectAt(t, facts, "$defs")
	assertFalse(t, objectAt(t, factDefinitions, "correlation"), "additionalProperties")
	assertFalse(t, objectAt(t, factDefinitions, "actor"), "additionalProperties")
	assertFalse(t, objectAt(t, factDefinitions, "payload"), "additionalProperties")
	requestProperties := objectAt(t, requests, "properties")
	assertFalse(t, objectAt(t, requestProperties, "correlation"), "additionalProperties")
	assertFalse(t, objectAt(t, requestProperties, "payload"), "additionalProperties")

	if got := stringAt(t, facts, "x-rwms-topic"); got != "rwms.media.media.v1" {
		t.Fatalf("facts topic = %q", got)
	}
	if got := stringAt(t, requests, "x-rwms-topic"); got != "rwms.media.processing.v1" {
		t.Fatalf("processing topic = %q", got)
	}
	if got := stringAt(t, dlt, "x-rwms-topic"); got != "rwms.media.processing.v1.media-service-processing-v1.dlt" {
		t.Fatalf("processing DLT topic = %q", got)
	}
	if got := stringAt(t, ownerDlt, "x-rwms-topic"); got != "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt" {
		t.Fatalf("inventory owner DLT topic = %q", got)
	}
	for _, forbidden := range []string{"ownerId", "warehouseId", "actorRef", "payload"} {
		if strings.Contains(string(ownerDltRaw), forbidden) {
			t.Fatalf("sanitized inventory owner DLT schema exposes forbidden %q", forbidden)
		}
	}
	if strings.Contains(string(dltRaw), "ownerId") || strings.Contains(string(dltRaw), "objectKey") ||
		strings.Contains(string(dltRaw), "actorRef") || strings.Contains(string(dltRaw), "payload") {
		t.Fatal("sanitized processing DLT schema exposes forbidden source data")
	}
	if got := stringAt(t, objectAt(t, requestProperties, "eventType"), "const"); got != "media.processing.request.v1" {
		t.Fatalf("processing event type = %q", got)
	}
	if got := stringAt(t, objectAt(t, requestProperties, "aggregateType"), "const"); got != "PROCESSING_JOB" {
		t.Fatalf("processing aggregate type = %q", got)
	}
	if strings.Contains(string(factsRaw), "media.processing.request.v1") || strings.Contains(string(factsRaw), "PROCESSING_JOB") {
		t.Fatal("fact schema still contains processing-request union members")
	}
	if strings.Contains(string(requestsRaw), "media.media.uploaded.v1") || strings.Contains(string(requestsRaw), `"aggregateType":{"const":"MEDIA"}`) {
		t.Fatal("processing-request schema contains media fact members")
	}
}

func TestAsyncAPIReferencesExactSeparateSchemasAndTopics(t *testing.T) {
	events := eventsDirectory(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(events, "media-events.yaml")), &document); err != nil {
		t.Fatalf("decode media-events.yaml: %v", err)
	}
	if got := stringAt(t, document, "asyncapi"); got != "3.1.0" {
		t.Fatalf("asyncapi = %q, want 3.1.0", got)
	}
	channels := objectAt(t, document, "channels")
	if got := stringAt(t, objectAt(t, channels, "mediaFacts"), "address"); got != "rwms.media.media.v1" {
		t.Fatalf("mediaFacts address = %q", got)
	}
	if got := stringAt(t, objectAt(t, channels, "processingRequests"), "address"); got != "rwms.media.processing.v1" {
		t.Fatalf("processingRequests address = %q", got)
	}
	if got := stringAt(t, objectAt(t, channels, "processingDeadLetters"), "address"); got != "rwms.media.processing.v1.media-service-processing-v1.dlt" {
		t.Fatalf("processingDeadLetters address = %q", got)
	}
	if got := stringAt(t, objectAt(t, channels, "inventoryOwnerFacts"), "address"); got != "rwms.inventory.session.v1" {
		t.Fatalf("inventoryOwnerFacts address = %q", got)
	}
	if got := stringAt(t, objectAt(t, channels, "inventoryOwnerDeadLetters"), "address"); got != "rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt" {
		t.Fatalf("inventoryOwnerDeadLetters address = %q", got)
	}
	messages := objectAt(t, objectAt(t, document, "components"), "messages")
	factMessage := objectAt(t, messages, "MediaFactV1")
	requestMessage := objectAt(t, messages, "MediaProcessingRequestV1")
	dltMessage := objectAt(t, messages, "MediaProcessingDltV1")
	ownerDltMessage := objectAt(t, messages, "InventoryOwnerDltV1")
	ownerInputMessage := objectAt(t, messages, "InventoryFindingFactV1")
	if got := stringAt(t, objectAt(t, factMessage, "payload"), "$ref"); got != "./media/media-facts-v1.schema.json" {
		t.Fatalf("fact payload ref = %q", got)
	}
	if got := stringAt(t, objectAt(t, requestMessage, "payload"), "$ref"); got != "./media/media-processing-requests-v1.schema.json" {
		t.Fatalf("processing payload ref = %q", got)
	}
	if got := stringAt(t, objectAt(t, dltMessage, "payload"), "$ref"); got != "./media/media-processing-dlt-v1.schema.json" {
		t.Fatalf("processing DLT payload ref = %q", got)
	}
	if got := stringAt(t, objectAt(t, ownerDltMessage, "payload"), "$ref"); got != "./media/media-inventory-owner-dlt-v1.schema.json" {
		t.Fatalf("inventory owner DLT payload ref = %q", got)
	}
	assertInventoryOwnerInputNarrowing(t, objectAt(t, ownerInputMessage, "payload"),
		"./inventory/inventory-events-v1.schema.json")
	for name, message := range map[string]map[string]any{"fact": factMessage, "processing request": requestMessage} {
		if got := stringAt(t, message, "x-rwms-record-key"); got != "aggregateId" {
			t.Fatalf("%s record key = %q", name, got)
		}
	}
}

func TestOpenAPIParsesAndExposesOnlyApprovedRuntimePaths(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	if got := stringAt(t, document, "openapi"); got != "3.1.0" {
		t.Fatalf("openapi = %q, want 3.1.0", got)
	}
	paths := objectAt(t, document, "paths")
	approved := map[string]string{
		"/health/live":  "get",
		"/health/ready": "get",
		"/api/internal/media/v1/logistics/references/validate":      "post",
		"/api/media/v1/upload-sessions":                             "post",
		"/api/media/v1/upload-sessions/{uploadSessionId}/content":   "put",
		"/api/media/v1/upload-sessions/{uploadSessionId}/complete":  "post",
		"/api/media/v1/assets":                                      "get",
		"/api/media/v1/assets/{mediaId}/original":                   "get",
		"/api/media/v1/assets/{mediaId}/variants/{variant}/content": "get",
		"/api/media/v1/assets/{mediaId}/rotation":                   "post",
	}
	if len(paths) != len(approved) {
		t.Fatalf("OpenAPI paths = %d, want exactly %d", len(paths), len(approved))
	}
	for path, method := range approved {
		pathItem := objectAt(t, paths, path)
		if _, ok := pathItem[method]; !ok {
			t.Errorf("%s has no %s operation", path, method)
		}
		if _, ok := pathItem["delete"]; ok {
			t.Errorf("%s exposes forbidden delete operation", path)
		}
	}
}

func TestPublicMediaContractUsesOnlySameOriginOpaqueContentPaths(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	schemas := objectAt(t, objectAt(t, document, "components"), "schemas")
	if _, exists := schemas["SignedCapability"]; exists {
		t.Fatal("public contract still exposes a signed object-store capability")
	}
	uploadProperties := objectAt(t, objectAt(t, schemas, "UploadSession"), "properties")
	if uploadProperties["contentUploadUrl"] == nil || uploadProperties["uploadUrl"] != nil ||
		uploadProperties["formFields"] != nil {
		t.Fatalf("upload session properties = %#v", uploadProperties)
	}
	variantProperties := objectAt(t, objectAt(t, schemas, "SafeVariant"), "properties")
	if variantProperties["contentPath"] == nil || variantProperties["url"] != nil {
		t.Fatalf("safe variant properties = %#v", variantProperties)
	}
	raw := strings.ToLower(string(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml"))))
	for _, forbidden := range []string{"signedcapability", "presigned", "minio post policy", "formfields"} {
		if strings.Contains(raw, forbidden) {
			t.Fatalf("public media contract contains forbidden %q", forbidden)
		}
	}
}

func TestLogisticsReferenceValidationContractIsPrivateAndOpaque(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	paths := objectAt(t, document, "paths")
	operation := objectAt(t, objectAt(t, paths, "/api/internal/media/v1/logistics/references/validate"), "post")
	if got := stringAt(t, operation, "operationId"); got != "validateLogisticsMediaReferences" {
		t.Fatalf("operationId = %q", got)
	}
	schemas := objectAt(t, objectAt(t, document, "components"), "schemas")
	request := objectAt(t, schemas, "LogisticsMediaReferenceValidationRequest")
	response := objectAt(t, schemas, "LogisticsMediaReferenceValidation")
	for name, schema := range map[string]map[string]any{"request": request, "response": response} {
		if schema["additionalProperties"] != false {
			t.Errorf("%s schema allows undeclared properties", name)
		}
		properties := objectAt(t, schema, "properties")
		for _, forbidden := range []string{"ownerId", "url", "objectKey", "sourceObjectKey", "fileName", "contentType", "status"} {
			if _, present := properties[forbidden]; present {
				t.Errorf("%s schema exposes forbidden %q", name, forbidden)
			}
		}
	}
	reference := objectAt(t, schemas, "OpaqueReadyMediaReference")
	properties := objectAt(t, reference, "properties")
	if len(properties) != 2 || properties["mediaId"] == nil || properties["generation"] == nil {
		t.Fatalf("opaque reference properties = %#v", properties)
	}
}

func TestLegacyUnionSchemaRemainsByteImmutable(t *testing.T) {
	path := filepath.Join(eventsDirectory(t), "media", "media-events-v1.schema.json")
	sum := sha256.Sum256(readContract(t, path))
	if got := hex.EncodeToString(sum[:]); got != legacyUnionSchemaSHA256 {
		t.Fatalf("legacy media union schema SHA-256 = %s, want immutable %s", got, legacyUnionSchemaSHA256)
	}
}

func repositoryRoot(t *testing.T) string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller() did not return the contract test path")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(currentFile), "..", "..", "..", ".."))
}

func eventsDirectory(t *testing.T) string {
	t.Helper()
	return filepath.Join(repositoryRoot(t), "contracts", "events")
}

func readContract(t *testing.T, path string) []byte {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return content
}

func decodeJSONContract(t *testing.T, raw []byte) map[string]any {
	t.Helper()
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		t.Fatalf("decode JSON schema: %v", err)
	}
	return document
}

func objectAt(t *testing.T, document map[string]any, name string) map[string]any {
	t.Helper()
	value, ok := document[name].(map[string]any)
	if !ok {
		t.Fatalf("%q is %T, want object", name, document[name])
	}
	return value
}

func stringAt(t *testing.T, document map[string]any, name string) string {
	t.Helper()
	value, ok := document[name].(string)
	if !ok {
		t.Fatalf("%q is %T, want string", name, document[name])
	}
	return value
}

func assertFalse(t *testing.T, document map[string]any, name string) {
	t.Helper()
	value, ok := document[name].(bool)
	if !ok || value {
		t.Fatalf("%q = %#v, want false", name, document[name])
	}
}
