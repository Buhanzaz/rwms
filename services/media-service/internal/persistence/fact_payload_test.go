package persistence

import (
	"encoding/json"
	"sort"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"github.com/google/uuid"
)

func TestFactPayloadPreservesLegacyWireShapeAndAddsTaskEvidenceReference(t *testing.T) {
	warehouseID, ownerID, mediaID, folderID := uuid.New(), uuid.New(), uuid.New(), uuid.New()
	legacyFields := []string{
		"folderId", "generation", "kind", "mediaId", "ownerId", "ownerType",
		"rotationDegrees", "status", "warehouseId",
	}
	for _, ownerType := range []string{OwnerTypeInventoryFinding, OwnerTypeCabin} {
		t.Run(ownerType, func(t *testing.T) {
			payload := decodeFactPayload(t, AssetRecord{
				ID: mediaID, FolderID: folderID, OwnerType: ownerType, OwnerID: ownerID.String(),
				WarehouseID: warehouseID, Kind: media.KindImage,
			})
			assertExactPayloadFields(t, payload, legacyFields)
			if _, present := payload["clientReferenceId"]; present {
				t.Fatalf("%s fact unexpectedly contains clientReferenceId: %#v", ownerType, payload)
			}
		})
	}

	evidenceID := uuid.New()
	taskPayload := decodeFactPayload(t, AssetRecord{
		ID: mediaID, FolderID: folderID, OwnerType: OwnerTypeTaskBoardEntry, OwnerID: ownerID.String(),
		WarehouseID: warehouseID, Kind: media.KindImage, ClientReferenceID: &evidenceID,
	})
	assertExactPayloadFields(t, taskPayload, append(append([]string(nil), legacyFields...), "clientReferenceId"))
	if taskPayload["clientReferenceId"] != evidenceID.String() {
		t.Fatalf("task fact clientReferenceId = %#v, want %s", taskPayload["clientReferenceId"], evidenceID)
	}
}

func decodeFactPayload(t *testing.T, asset AssetRecord) map[string]any {
	t.Helper()
	body, _, err := envelopeForActor(uuid.New(), "media.media.ready.v1", "MEDIA", asset.ID, 2,
		nil, uuid.New(), time.Now().UTC(), factPayload(asset, media.StatusReady, 1, media.Rotation0))
	if err != nil {
		t.Fatalf("encode media fact: %v", err)
	}
	var envelope map[string]any
	if err := json.Unmarshal(body, &envelope); err != nil {
		t.Fatalf("decode media fact: %v", err)
	}
	payload, ok := envelope["payload"].(map[string]any)
	if !ok {
		t.Fatalf("media fact payload = %#v", envelope["payload"])
	}
	return payload
}

func assertExactPayloadFields(t *testing.T, payload map[string]any, want []string) {
	t.Helper()
	got := make([]string, 0, len(payload))
	for field := range payload {
		got = append(got, field)
	}
	sort.Strings(got)
	sort.Strings(want)
	if len(got) != len(want) {
		t.Fatalf("payload fields = %v, want %v", got, want)
	}
	for index := range got {
		if got[index] != want[index] {
			t.Fatalf("payload fields = %v, want %v", got, want)
		}
	}
}
