package contract

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

func TestMediaFactSchemaValidatesStrictFixtures(t *testing.T) {
	schema := compileActualSchema(t, "media-facts-v1.schema.json")
	valid := validFactFixture()
	assertSchemaAccepts(t, schema, valid)
	grouped := cloneFixture(t, valid)
	grouped["payload"].(map[string]any)["folderId"] = "dd344d67-9502-4716-865d-592e61131b9c"
	assertSchemaAccepts(t, schema, grouped)
	cabin := cloneFixture(t, valid)
	cabin["payload"].(map[string]any)["ownerType"] = "CABIN"
	assertSchemaAccepts(t, schema, cabin)
	maintenance := cloneFixture(t, valid)
	maintenance["payload"].(map[string]any)["ownerType"] = "MAINTENANCE_REPAIR"
	assertSchemaAccepts(t, schema, maintenance)
	workerEvidence := cloneFixture(t, valid)
	workerEvidence["payload"].(map[string]any)["ownerType"] = "TASK_BOARD_ENTRY"
	workerEvidence["payload"].(map[string]any)["clientReferenceId"] = "33a0f9d1-0ad2-42d1-bb94-36284833f622"
	workerEvidence["actorRef"].(map[string]any)["principalType"] = "WORKER"
	assertSchemaAccepts(t, schema, workerEvidence)
	shiftEvidence := cloneFixture(t, workerEvidence)
	shiftEvidence["payload"].(map[string]any)["ownerType"] = "DRIVER_SHIFT"
	assertSchemaAccepts(t, schema, shiftEvidence)
	logistics := cloneFixture(t, valid)
	logistics["payload"].(map[string]any)["ownerType"] = "LOGISTICS_TRANSFER"
	logistics["payload"].(map[string]any)["ownerId"] =
		"d96d49c9-9e30-4d7c-b298-1687acb06a08:11bbb7f0-cf00-47cf-822e-21b362a5b206"
	assertSchemaAccepts(t, schema, logistics)
	deleted := cloneFixture(t, valid)
	deleted["eventType"] = "media.media.deleted.v1"
	deleted["payload"].(map[string]any)["status"] = "DELETED"
	assertSchemaAccepts(t, schema, deleted)

	tests := map[string]func(map[string]any){
		"unknown root property": func(value map[string]any) { value["credential"] = "forbidden" },
		"malformed event id":    func(value map[string]any) { value["eventId"] = "not-a-uuid" },
		"processing event union": func(value map[string]any) {
			value["eventType"] = "media.processing.request.v1"
		},
		"wrong aggregate family": func(value map[string]any) { value["aggregateType"] = "PROCESSING_JOB" },
		"PII in actor": func(value map[string]any) {
			value["actorRef"].(map[string]any)["email"] = "operator@example.invalid"
		},
		"unapproved owner type": func(value map[string]any) {
			value["payload"].(map[string]any)["ownerType"] = "INSPECTION"
		},
		"logistics UUID instead of structured owner": func(value map[string]any) {
			value["payload"].(map[string]any)["ownerType"] = "LOGISTICS_RETURN"
		},
		"maintenance composite owner": func(value map[string]any) {
			value["payload"].(map[string]any)["ownerType"] = "MAINTENANCE_ESTIMATE"
			value["payload"].(map[string]any)["ownerId"] =
				"d96d49c9-9e30-4d7c-b298-1687acb06a08:11bbb7f0-cf00-47cf-822e-21b362a5b206"
		},
		"malformed optional folder": func(value map[string]any) {
			value["payload"].(map[string]any)["folderId"] = "not-a-uuid"
		},
		"object provenance leakage": func(value map[string]any) {
			value["payload"].(map[string]any)["sourceObjectKey"] = "media/private/source.jpg"
		},
		"task evidence missing stable reference": func(value map[string]any) {
			value["payload"].(map[string]any)["ownerType"] = "TASK_BOARD_ENTRY"
		},
		"shift evidence missing stable reference": func(value map[string]any) {
			value["payload"].(map[string]any)["ownerType"] = "DRIVER_SHIFT"
		},
		"non task evidence has stable reference": func(value map[string]any) {
			value["payload"].(map[string]any)["clientReferenceId"] = "33a0f9d1-0ad2-42d1-bb94-36284833f622"
		},
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			fixture := cloneFixture(t, valid)
			mutate(fixture)
			assertSchemaRejects(t, schema, fixture)
		})
	}
}

func TestMediaSchemasAcceptPreV8NonTaskPayloadWithoutClientReferenceID(t *testing.T) {
	for _, fileName := range []string{"media-events-v1.schema.json", "media-facts-v1.schema.json"} {
		t.Run(fileName, func(t *testing.T) {
			schema := compileActualSchema(t, fileName)
			legacy := validFactFixture()
			delete(legacy["payload"].(map[string]any), "clientReferenceId")
			assertSchemaAccepts(t, schema, legacy)
		})
	}
}

func TestMediaProcessingRequestSchemaValidatesStrictFixtures(t *testing.T) {
	schema := compileActualSchema(t, "media-processing-requests-v1.schema.json")
	valid := validProcessingRequestFixture()
	assertSchemaAccepts(t, schema, valid)

	tests := map[string]func(map[string]any){
		"unknown root property": func(value map[string]any) { value["retryCount"] = 1 },
		"wrong event type":      func(value map[string]any) { value["eventType"] = "media.media.uploaded.v1" },
		"wrong aggregate family": func(value map[string]any) {
			value["aggregateType"] = "MEDIA"
		},
		"aggregate version other than one": func(value map[string]any) { value["aggregateVersion"] = 2 },
		"non-null actor": func(value map[string]any) {
			value["actorRef"] = map[string]any{
				"subjectId":       "39ce1d2f-261a-4fe1-bf5c-2e28f02b4c28",
				"principalType":   "USER",
				"profileRevision": nil,
			}
		},
		"generation zero": func(value map[string]any) {
			value["payload"].(map[string]any)["generation"] = 0
		},
		"unpinned source": func(value map[string]any) {
			value["payload"].(map[string]any)["sourceVersionId"] = ""
		},
		"object key leakage": func(value map[string]any) {
			value["payload"].(map[string]any)["objectKey"] = "media/private/source.jpg"
		},
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			fixture := cloneFixture(t, valid)
			mutate(fixture)
			assertSchemaRejects(t, schema, fixture)
		})
	}
}

func TestMediaProcessingDLTSchemaValidatesSanitizedFixtures(t *testing.T) {
	schema := compileActualSchema(t, "media-processing-dlt-v1.schema.json")
	valid := map[string]any{
		"failureCode":   "INVALID_PROCESSING_REQUEST",
		"messageSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"recordedAt":    "2026-07-17T12:34:56Z",
	}
	assertSchemaAccepts(t, schema, valid)
	exhausted := cloneFixture(t, valid)
	exhausted["failureCode"] = "PROCESSING_ATTEMPT_EXHAUSTED"
	assertSchemaAccepts(t, schema, exhausted)

	tests := map[string]func(map[string]any){
		"owner leakage": func(value map[string]any) { value["ownerId"] = "d96d49c9-9e30-4d7c-b298-1687acb06a08" },
		"payload leakage": func(value map[string]any) {
			value["payload"] = map[string]any{"sourceVersionId": "secret-version"}
		},
		"unknown failure code": func(value map[string]any) { value["failureCode"] = "RAW_BROKER_ERROR" },
		"uppercase hash": func(value map[string]any) {
			value["messageSha256"] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
		},
		"invalid timestamp": func(value map[string]any) { value["recordedAt"] = "yesterday" },
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			fixture := cloneFixture(t, valid)
			mutate(fixture)
			assertSchemaRejects(t, schema, fixture)
		})
	}
}

func TestMediaInventoryOwnerDLTSchemaValidatesSanitizedFixtures(t *testing.T) {
	schema := compileActualSchema(t, "media-inventory-owner-dlt-v1.schema.json")
	valid := map[string]any{
		"failureCode":   "INVALID_INVENTORY_OWNER_FACT",
		"messageSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"recordedAt":    "2026-07-17T12:34:56Z",
	}
	assertSchemaAccepts(t, schema, valid)
	for name, mutate := range map[string]func(map[string]any){
		"owner leakage":       func(value map[string]any) { value["ownerId"] = "d96d49c9-9e30-4d7c-b298-1687acb06a08" },
		"warehouse leakage":   func(value map[string]any) { value["warehouseId"] = "74db62b2-89fe-4d5e-9f51-b570aa4dc28f" },
		"raw payload leakage": func(value map[string]any) { value["payload"] = map[string]any{"active": true} },
		"unknown failure":     func(value map[string]any) { value["failureCode"] = "RAW_DATABASE_ERROR" },
	} {
		t.Run(name, func(t *testing.T) {
			fixture := cloneFixture(t, valid)
			mutate(fixture)
			assertSchemaRejects(t, schema, fixture)
		})
	}
}

func TestCabinCoverFactSchemaIsStrictAndCarriesNoObjectLocator(t *testing.T) {
	schema := compileActualSchema(t, "cabin-photo-facts-v1.schema.json")
	valid := map[string]any{
		"envelopeVersion":  2,
		"eventId":          "8df83c63-c41c-43bb-ae5e-17b75986efe1",
		"eventType":        "media.cabin.cover-changed.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       "2026-07-30T12:34:56Z",
		"producer":         "media-service",
		"aggregateType":    "CABIN_PHOTO_LIBRARY",
		"aggregateId":      "11bbb7f0-cf00-47cf-822e-21b362a5b206",
		"aggregateVersion": 2,
		"correlation": map[string]any{
			"correlationId": "a9b456b6-becf-40b6-9874-4293c041a630",
			"causationId":   nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"cabinId":              "11bbb7f0-cf00-47cf-822e-21b362a5b206",
			"warehouseId":          "7f414608-e94d-4f73-8f92-54fa675e8af0",
			"mediaId":              "33a0f9d1-0ad2-42d1-bb94-36284833f622",
			"generation":           1,
			"taskBoardEntryId":     "d96d49c9-9e30-4d7c-b298-1687acb06a08",
			"previousCoverMediaId": nil,
			"changedAt":            "2026-07-30T12:34:56Z",
		},
	}
	assertSchemaAccepts(t, schema, valid)
	direct := cloneFixture(t, valid)
	direct["payload"].(map[string]any)["taskBoardEntryId"] = nil
	assertSchemaAccepts(t, schema, direct)
	for name, mutate := range map[string]func(map[string]any){
		"object key leakage": func(value map[string]any) {
			value["payload"].(map[string]any)["objectKey"] = "private/cabin.jpg"
		},
		"wrong aggregate": func(value map[string]any) {
			value["aggregateType"] = "MEDIA"
		},
		"zero version": func(value map[string]any) {
			value["aggregateVersion"] = 0
		},
	} {
		t.Run(name, func(t *testing.T) {
			fixture := cloneFixture(t, valid)
			mutate(fixture)
			assertSchemaRejects(t, schema, fixture)
		})
	}
}

func compileActualSchema(t *testing.T, fileName string) *jsonschema.Schema {
	t.Helper()
	path := filepath.Join(eventsDirectory(t), "media", fileName)
	document, err := jsonschema.UnmarshalJSON(bytes.NewReader(readContract(t, path)))
	if err != nil {
		t.Fatalf("decode JSON schema %s: %v", path, err)
	}
	compiler := jsonschema.NewCompiler()
	compiler.AssertFormat()
	if err := compiler.AddResource(path, document); err != nil {
		t.Fatalf("add JSON schema resource %s: %v", path, err)
	}
	schema, err := compiler.Compile(path)
	if err != nil {
		t.Fatalf("compile JSON schema %s: %v", path, err)
	}
	return schema
}

func assertSchemaAccepts(t *testing.T, schema *jsonschema.Schema, fixture map[string]any) {
	t.Helper()
	if err := schema.Validate(asJSONValue(t, fixture)); err != nil {
		t.Fatalf("schema rejected valid fixture: %v\nfixture: %#v", err, fixture)
	}
}

func assertSchemaRejects(t *testing.T, schema *jsonschema.Schema, fixture map[string]any) {
	t.Helper()
	if err := schema.Validate(asJSONValue(t, fixture)); err == nil {
		t.Fatalf("schema accepted invalid fixture: %#v", fixture)
	}
}

func asJSONValue(t *testing.T, fixture map[string]any) any {
	t.Helper()
	raw, err := json.Marshal(fixture)
	if err != nil {
		t.Fatalf("marshal fixture: %v", err)
	}
	value, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("decode fixture as JSON value: %v", err)
	}
	return value
}

func cloneFixture(t *testing.T, fixture map[string]any) map[string]any {
	t.Helper()
	raw, err := json.Marshal(fixture)
	if err != nil {
		t.Fatalf("marshal fixture clone: %v", err)
	}
	var clone map[string]any
	if err := json.Unmarshal(raw, &clone); err != nil {
		t.Fatalf("decode fixture clone: %v", err)
	}
	return clone
}

func validFactFixture() map[string]any {
	return map[string]any{
		"envelopeVersion":  2,
		"eventId":          "8df83c63-c41c-43bb-ae5e-17b75986efe1",
		"eventType":        "media.media.uploaded.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       "2026-07-17T12:34:56Z",
		"producer":         "media-service",
		"aggregateType":    "MEDIA",
		"aggregateId":      "11bbb7f0-cf00-47cf-822e-21b362a5b206",
		"aggregateVersion": 2,
		"correlation": map[string]any{
			"correlationId": "a9b456b6-becf-40b6-9874-4293c041a630",
			"causationId":   nil,
		},
		"actorRef": map[string]any{
			"subjectId":       "39ce1d2f-261a-4fe1-bf5c-2e28f02b4c28",
			"principalType":   "USER",
			"profileRevision": nil,
		},
		"payload": map[string]any{
			"mediaId":           "11bbb7f0-cf00-47cf-822e-21b362a5b206",
			"ownerType":         "INVENTORY_FINDING",
			"ownerId":           "d96d49c9-9e30-4d7c-b298-1687acb06a08",
			"warehouseId":       "7f414608-e94d-4f73-8f92-54fa675e8af0",
			"clientReferenceId": nil,
			"kind":              "IMAGE",
			"status":            "PROCESSING",
			"generation":        1,
			"rotationDegrees":   0,
		},
	}
}

func validProcessingRequestFixture() map[string]any {
	return map[string]any{
		"envelopeVersion":  2,
		"eventId":          "7cb1ac58-7500-4ef2-b88e-84db6ec234a9",
		"eventType":        "media.processing.request.v1",
		"eventVersion":     1,
		"occurredAt":       nil,
		"recordedAt":       "2026-07-17T12:34:56Z",
		"producer":         "media-service",
		"aggregateType":    "PROCESSING_JOB",
		"aggregateId":      "59e80bb0-86ad-4dfb-b2d7-ed19e930945e",
		"aggregateVersion": 1,
		"correlation": map[string]any{
			"correlationId": "a9b456b6-becf-40b6-9874-4293c041a630",
			"causationId":   nil,
		},
		"actorRef": nil,
		"payload": map[string]any{
			"processingJobId": "59e80bb0-86ad-4dfb-b2d7-ed19e930945e",
			"mediaId":         "11bbb7f0-cf00-47cf-822e-21b362a5b206",
			"warehouseId":     "7f414608-e94d-4f73-8f92-54fa675e8af0",
			"kind":            "IMAGE",
			"processingKind":  "INITIAL",
			"generation":      1,
			"rotationDegrees": 0,
			"sourceVersionId": "01J2M7Y9W5B3N9W3NQ6R2A4X9Z",
		},
	}
}
