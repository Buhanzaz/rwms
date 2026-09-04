package contract

import (
	"path/filepath"
	"strings"
	"testing"

	"go.yaml.in/yaml/v3"
)

func TestContractorTaskExecutionMediaContractIsExactAndPrivate(t *testing.T) {
	root := repositoryRoot(t)
	var document map[string]any
	if err := yaml.Unmarshal(readContract(t, filepath.Join(root, "contracts", "openapi", "media-service.yaml")), &document); err != nil {
		t.Fatalf("decode media-service.yaml: %v", err)
	}
	paths := objectAt(t, document, "paths")
	upload := objectAt(t, objectAt(t, paths,
		"/api/internal/media/v1/logistics/contractor-task-executions/{entryId}/workers/{workerId}/evidence/{evidenceId}"), "post")
	content := objectAt(t, objectAt(t, paths,
		"/api/internal/media/v1/logistics/contractor-task-executions/{entryId}/workers/{workerId}/assets/{mediaId}/generations/{generation}/variants/{variant}/content"), "get")

	for name, operation := range map[string]map[string]any{"upload": upload, "content": content} {
		description := stringAt(t, operation, "description")
		for _, required := range []string{"sub=client_id=logistics-service", "exactly media.logistics", "task-board", "worker"} {
			if !strings.Contains(description, required) {
				t.Errorf("%s description does not contain %q: %s", name, required, description)
			}
		}
	}
	if !strings.Contains(stringAt(t, upload, "description"), "never expose") ||
		!strings.Contains(stringAt(t, content, "description"), "not a general media reader") {
		t.Fatal("contractor execution operations must explicitly deny general storage/media exposure")
	}

	requestBody := objectAt(t, upload, "requestBody")
	requestContent := objectAt(t, requestBody, "content")
	if len(requestContent) != 2 || requestContent["image/jpeg"] == nil || requestContent["image/webp"] == nil {
		t.Fatalf("contractor evidence request content = %#v", requestContent)
	}
	uploadResponses := objectAt(t, upload, "responses")
	for _, status := range []string{"200", "201", "400", "401", "403", "409", "415", "503"} {
		if uploadResponses[status] == nil {
			t.Errorf("contractor evidence upload is missing %s response", status)
		}
	}
	contentResponses := objectAt(t, content, "responses")
	for _, status := range []string{"200", "400", "401", "403", "404", "503"} {
		if contentResponses[status] == nil {
			t.Errorf("contractor evidence content is missing %s response", status)
		}
	}

	components := objectAt(t, document, "components")
	parameters := objectAt(t, components, "parameters")
	for _, parameter := range []string{
		"IdempotencyKey", "ContentSHA256", "ContentLength", "ContractorTaskEntryId",
		"ContractorWorkerId", "ContractorEvidenceId", "ContractorTaskGeneration", "ContractorTaskVariant",
	} {
		if parameters[parameter] == nil {
			t.Errorf("contractor execution contract is missing %s parameter", parameter)
		}
	}
	variant := objectAt(t, parameters, "ContractorTaskVariant")
	variantSchema := objectAt(t, variant, "schema")
	if got := stringSliceAt(t, variantSchema, "enum"); strings.Join(got, ",") != "SMALL,MEDIUM,LARGE" {
		t.Fatalf("contractor execution variants = %#v", got)
	}

	schemas := objectAt(t, components, "schemas")
	receipt := objectAt(t, schemas, "ContractorTaskEvidenceReceipt")
	if receipt["additionalProperties"] != false {
		t.Fatal("contractor evidence receipt must reject additional properties")
	}
	receiptProperties := objectAt(t, receipt, "properties")
	if len(receiptProperties) != 3 || receiptProperties["mediaId"] == nil ||
		receiptProperties["generation"] == nil || receiptProperties["status"] == nil {
		t.Fatalf("contractor evidence receipt properties = %#v", receiptProperties)
	}
	for _, forbidden := range []string{"companyId", "objectKey", "bucket", "url", "path", "token", "uploadSessionId", "workerId", "entryId"} {
		if receiptProperties[forbidden] != nil {
			t.Errorf("contractor evidence receipt exposes %s", forbidden)
		}
	}
}
