package contract

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

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
		"/health/live":                                                                                     "get",
		"/health/ready":                                                                                    "get",
		"/api/media/v1/events":                                                                             "get",
		"/api/internal/media/v1/owner-proofs":                                                              "post",
		"/api/internal/media/v1/asset-imports/preflight":                                                   "post",
		"/api/internal/media/v1/asset-imports/{jobId}":                                                     "get",
		"/api/internal/media/v1/asset-imports/{jobId}/activate":                                            "post",
		"/api/internal/media/v1/asset-imports/{jobId}/retry":                                               "post",
		"/api/internal/media/v1/asset-imports/{jobId}/replace-sources":                                     "post",
		"/api/internal/media/v1/logistics/references/validate":                                             "post",
		"/api/internal/media/v1/logistics/cabin-presentations/snapshots":                                   "post",
		"/api/internal/media/v1/logistics/cabins/{cabinId}/cover-from-task-evidence":                       "post",
		"/api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content": "get",
		"/api/media/v1/upload-sessions":                                                                    "post",
		"/api/media/v1/upload-sessions/{uploadSessionId}/content":                                          "put",
		"/api/media/v1/upload-sessions/{uploadSessionId}/complete":                                         "post",
		"/api/media/v1/assets":                                                                             "get",
		"/api/media/v1/cabin-covers":                                                                       "post",
		"/api/media/v1/assets/{mediaId}/original":                                                          "get",
		"/api/media/v1/assets/{mediaId}/variants/{variant}/content":                                        "get",
		"/api/media/v1/assets/{mediaId}/deletion":                                                          "post",
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

func TestTaskEvidenceContractRequiresOneWorkerOrDriverScope(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	upload := objectAt(t, objectAt(t, objectAt(t, document, "paths"), "/api/media/v1/upload-sessions"), "post")
	description := stringAt(t, upload, "description")
	for _, required := range []string{"WORKER", "exactly one", "worker.tasks", "driver.tasks", "worker_id"} {
		if !strings.Contains(description, required) {
			t.Fatalf("upload authorization description %q does not contain %q", description, required)
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
	coverRequest := objectAt(t, schemas, "CabinCoverBatchRequest")
	coverRequestProperties := objectAt(t, coverRequest, "properties")
	cabinIDs := objectAt(t, coverRequestProperties, "cabinIds")
	if cabinIDs["maxItems"] != 200 || cabinIDs["uniqueItems"] != true {
		t.Fatalf("cabin cover batch bounds = %#v", cabinIDs)
	}
	coverProjection := objectAt(t, schemas, "CabinCoverProjection")
	coverProperties := objectAt(t, coverProjection, "properties")
	if coverProperties["photoCount"] == nil || coverProperties["cover"] == nil ||
		coverProperties["previews"] == nil {
		t.Fatalf("cabin cover projection = %#v", coverProperties)
	}
	previews := objectAt(t, coverProperties, "previews")
	if previews["maxItems"] != 100 || previews["uniqueItems"] != true {
		t.Fatalf("cabin preview bounds = %#v", previews)
	}
	coverVariant := objectAt(t, schemas, "CabinCoverVariant")
	coverVariantProperties := objectAt(t, coverVariant, "properties")
	if coverVariantProperties["contentPath"] == nil || coverVariantProperties["mediaId"] == nil ||
		coverVariantProperties["generation"] == nil || coverVariantProperties["url"] != nil {
		t.Fatalf("cabin cover variant properties = %#v", coverVariantProperties)
	}
	kind := objectAt(t, coverVariantProperties, "kind")
	if kind["const"] != "SMALL" {
		t.Fatalf("cabin preview kind = %#v, want SMALL only", kind)
	}
	raw := strings.ToLower(string(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml"))))
	for _, forbidden := range []string{"signedcapability", "presigned", "minio post policy", "formfields"} {
		if strings.Contains(raw, forbidden) {
			t.Fatalf("public media contract contains forbidden %q", forbidden)
		}
	}
}

func TestPublicMediaContractExposesAllCanonicalOwnerScopes(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	schemas := objectAt(t, objectAt(t, document, "components"), "schemas")
	ownerType := objectAt(t, schemas, "MediaOwnerType")
	ownerContext := objectAt(t, schemas, "MediaOwnerContext")
	if got := stringSliceAt(t, ownerType, "enum"); !equalStrings(got, []string{
		"INVENTORY_FINDING", "CABIN", "MAINTENANCE_ESTIMATE", "MAINTENANCE_REPAIR",
		"MAINTENANCE_ACCEPTANCE", "MAINTENANCE_CATALOG_NODE", "LOGISTICS_RETURN",
		"LOGISTICS_SHIPMENT", "LOGISTICS_TRANSFER", "TASK_BOARD_ENTRY",
	}) {
		t.Fatalf("media owner types = %#v", got)
	}
	if got := stringSliceAt(t, ownerContext, "enum"); !equalStrings(got, []string{
		"INSPECTION", "WAREHOUSE", "ESTIMATE", "REPAIR", "ACCEPTANCE", "CATALOG",
		"RETURN_INSPECTION", "SHIPMENT", "TRANSFER", "WORK_RESULT",
	}) {
		t.Fatalf("media owner contexts = %#v", got)
	}
	upload := objectAt(t, schemas, "CreateUploadSessionRequest")
	asset := objectAt(t, schemas, "MediaAsset")
	uploadProperties := objectAt(t, upload, "properties")
	assetProperties := objectAt(t, asset, "properties")
	if _, ok := uploadProperties["folderId"]; !ok {
		t.Fatal("upload contract does not expose optional folderId")
	}
	if _, ok := assetProperties["folderId"]; !ok {
		t.Fatal("media asset contract does not expose folderId")
	}
	if strings.Contains(stringSliceJSON(t, upload["required"]), "folderId") {
		t.Fatal("upload folderId must remain optional for legacy clients")
	}
	if !strings.Contains(stringSliceJSON(t, asset["required"]), "folderId") {
		t.Fatal("media asset folderId must be required")
	}
	pairs, ok := upload["oneOf"].([]any)
	if !ok || len(pairs) != 10 {
		t.Fatalf("upload owner scope pairs = %#v", upload["oneOf"])
	}
	wire, err := json.Marshal(pairs)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{
		`"ownerType":{"const":"INVENTORY_FINDING"}`, `"context":{"const":"INSPECTION"}`,
		`"ownerType":{"const":"CABIN"}`, `"context":{"const":"WAREHOUSE"}`,
		`"ownerType":{"const":"MAINTENANCE_ESTIMATE"}`, `"context":{"const":"ESTIMATE"}`,
		`"ownerType":{"const":"MAINTENANCE_REPAIR"}`, `"context":{"const":"REPAIR"}`,
		`"ownerType":{"const":"MAINTENANCE_ACCEPTANCE"}`, `"context":{"const":"ACCEPTANCE"}`,
		`"ownerType":{"const":"MAINTENANCE_CATALOG_NODE"}`, `"context":{"const":"CATALOG"}`,
		`"ownerType":{"const":"LOGISTICS_RETURN"}`, `"context":{"const":"RETURN_INSPECTION"}`,
		`"ownerType":{"const":"LOGISTICS_SHIPMENT"}`, `"context":{"const":"SHIPMENT"}`,
		`"ownerType":{"const":"LOGISTICS_TRANSFER"}`, `"context":{"const":"TRANSFER"}`,
		`"ownerType":{"const":"TASK_BOARD_ENTRY"}`, `"context":{"const":"WORK_RESULT"}`,
	} {
		if !strings.Contains(string(wire), required) {
			t.Errorf("upload owner scope pairs do not contain %s: %s", required, wire)
		}
	}
}

func stringSliceJSON(t *testing.T, value any) string {
	t.Helper()
	wire, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(wire)
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

func TestLogisticsCabinPresentationContractIsPrivateAndOpaque(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	paths := objectAt(t, document, "paths")
	snapshot := objectAt(t, objectAt(t, paths,
		"/api/internal/media/v1/logistics/cabin-presentations/snapshots"), "post")
	if got := stringAt(t, snapshot, "operationId"); got != "readLogisticsCabinPresentationSnapshots" {
		t.Fatalf("snapshot operationId = %q", got)
	}
	content := objectAt(t, objectAt(t, paths,
		"/api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content"), "get")
	if got := stringAt(t, content, "operationId"); got != "getLogisticsCabinPresentationVariantContent" {
		t.Fatalf("content operationId = %q", got)
	}
	responses := objectAt(t, content, "responses")
	if responses["404"] == nil {
		t.Fatal("private cabin presentation content route must fold mismatches into 404")
	}

	schemas := objectAt(t, objectAt(t, document, "components"), "schemas")
	request := objectAt(t, schemas, "CabinPresentationSnapshotRequest")
	if request["additionalProperties"] != false {
		t.Fatal("cabin presentation request allows undeclared properties")
	}
	requestCabinIDs := objectAt(t, objectAt(t, request, "properties"), "cabinIds")
	if requestCabinIDs["minItems"] != 1 || requestCabinIDs["maxItems"] != 100 || requestCabinIDs["uniqueItems"] != true {
		t.Fatalf("cabin presentation request bounds = %#v", requestCabinIDs)
	}
	for name, schemaName := range map[string]string{
		"snapshot page": "CabinPresentationSnapshotPage",
		"snapshot":      "CabinPresentationSnapshot",
		"photo":         "CabinPresentationPhoto",
	} {
		schema := objectAt(t, schemas, schemaName)
		if schema["additionalProperties"] != false {
			t.Errorf("%s allows undeclared properties", name)
		}
		properties := objectAt(t, schema, "properties")
		for _, forbidden := range []string{"url", "contentPath", "objectKey", "sourceObjectKey", "bucket", "path", "signedUrl", "fileName", "contentType", "status"} {
			if _, present := properties[forbidden]; present {
				t.Errorf("%s exposes forbidden %q", name, forbidden)
			}
		}
	}
	photoProperties := objectAt(t, objectAt(t, schemas, "CabinPresentationPhoto"), "properties")
	variants := objectAt(t, photoProperties, "availableVariants")
	if variants["minItems"] != 1 || variants["maxItems"] != 2 || variants["uniqueItems"] != true {
		t.Fatalf("availableVariants bounds = %#v", variants)
	}
	if got := stringSliceAt(t, objectAt(t, variants, "items"), "enum"); !equalStrings(got, []string{"SMALL", "LARGE"}) {
		t.Fatalf("availableVariants enum = %#v", got)
	}
}

func TestServiceOwnerProofAndDeletionContractsAreClosedAndOpaque(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	paths := objectAt(t, document, "paths")
	proofOperation := objectAt(t, objectAt(t, paths, "/api/internal/media/v1/owner-proofs"), "post")
	if got := stringAt(t, proofOperation, "operationId"); got != "upsertServiceMediaOwnerProof" {
		t.Fatalf("proof operationId = %q", got)
	}
	deleteOperation := objectAt(t, objectAt(t, paths, "/api/media/v1/assets/{mediaId}/deletion"), "post")
	if got := stringAt(t, deleteOperation, "operationId"); got != "deleteMedia" {
		t.Fatalf("delete operationId = %q", got)
	}
	schemas := objectAt(t, objectAt(t, document, "components"), "schemas")
	for _, name := range []string{"ServiceOwnerProofRequest", "ServiceOwnerProof"} {
		schema := objectAt(t, schemas, name)
		if schema["additionalProperties"] != false {
			t.Fatalf("%s allows undeclared properties", name)
		}
		properties := objectAt(t, schema, "properties")
		for _, forbidden := range []string{"objectKey", "sourceObjectKey", "url", "contentPath", "fileName"} {
			if _, found := properties[forbidden]; found {
				t.Errorf("%s exposes forbidden %q", name, forbidden)
			}
		}
		if pairs, ok := schema["oneOf"].([]any); !ok || len(pairs) != 2 {
			t.Fatalf("%s identity union = %#v", name, schema["oneOf"])
		}
	}
	requestProperties := objectAt(t, objectAt(t, schemas, "ServiceOwnerProofRequest"), "properties")
	warehouse := objectAt(t, requestProperties, "warehouseId")
	if description := stringAt(t, warehouse, "description"); !strings.Contains(description, "destinationWarehouseId") || !strings.Contains(description, "destination/receiving") {
		t.Fatalf("owner proof warehouse semantics = %q", description)
	}
	deleteRequest := objectAt(t, schemas, "DeleteRequest")
	if deleteRequest["additionalProperties"] != false || objectAt(t,
		objectAt(t, deleteRequest, "properties"), "expectedVersion")["minimum"] != 1 {
		t.Fatalf("delete request = %#v", deleteRequest)
	}
}

func TestLegacyUnionSchemaCarriesTheExpandedOwnerEnum(t *testing.T) {
	path := filepath.Join(eventsDirectory(t), "media", "media-events-v1.schema.json")
	document := decodeJSONContract(t, readContract(t, path))
	payload := objectAt(t, objectAt(t, document, "$defs"), "payload")
	ownerType := objectAt(t, objectAt(t, payload, "properties"), "ownerType")
	if got := stringSliceAt(t, ownerType, "enum"); !equalStrings(got, []string{
		"INVENTORY_FINDING", "CABIN", "MAINTENANCE_ESTIMATE", "MAINTENANCE_REPAIR",
		"MAINTENANCE_ACCEPTANCE", "MAINTENANCE_CATALOG_NODE", "LOGISTICS_RETURN",
		"LOGISTICS_SHIPMENT", "LOGISTICS_TRANSFER", "TASK_BOARD_ENTRY",
	}) {
		t.Fatalf("legacy union media owner types = %#v", got)
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

func stringSliceAt(t *testing.T, document map[string]any, name string) []string {
	t.Helper()
	values, ok := document[name].([]any)
	if !ok {
		t.Fatalf("%q is %T, want array", name, document[name])
	}
	result := make([]string, len(values))
	for index, value := range values {
		text, ok := value.(string)
		if !ok {
			t.Fatalf("%q[%d] is %T, want string", name, index, value)
		}
		result[index] = text
	}
	return result
}

func equalStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

func assertFalse(t *testing.T, document map[string]any, name string) {
	t.Helper()
	value, ok := document[name].(bool)
	if !ok || value {
		t.Fatalf("%q = %#v, want false", name, document[name])
	}
}
