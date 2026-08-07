package media

import "testing"

func TestInitialProcessingMakesFirstGenerationCurrentOnlyAfterSuccess(t *testing.T) {
	asset := Asset{ID: "m-1", Status: StatusUploading}

	job, err := asset.QueueInitialProcessing()
	if err != nil {
		t.Fatalf("QueueInitialProcessing() error = %v", err)
	}
	if job.Kind != ProcessingInitial || job.Generation != 1 || asset.Status != StatusProcessing {
		t.Fatalf("unexpected initial job/state: %#v / %#v", job, asset)
	}
	if asset.CurrentGeneration != 0 {
		t.Fatalf("current generation = %d, want 0 until processing succeeds", asset.CurrentGeneration)
	}

	if err := asset.CompleteProcessing(job); err != nil {
		t.Fatalf("CompleteProcessing() error = %v", err)
	}
	if asset.Status != StatusReady || asset.CurrentGeneration != 1 || asset.Rotation != Rotation0 {
		t.Fatalf("completed asset = %#v, want ready generation 1", asset)
	}
}

func TestInitialProcessingRejectsAnyNonInitialOrRotatedJob(t *testing.T) {
	asset := Asset{ID: "m-1", Status: StatusUploading}
	job, err := asset.QueueInitialProcessing()
	if err != nil {
		t.Fatalf("QueueInitialProcessing() error = %v", err)
	}

	unsupported := job
	unsupported.Kind = "ROTATION"
	if err := asset.CompleteProcessing(unsupported); err == nil {
		t.Fatal("CompleteProcessing() error = nil, want unsupported job rejection")
	}
	if asset.Status != StatusProcessing || asset.CurrentGeneration != 0 || asset.Rotation != Rotation0 {
		t.Fatalf("unsupported job changed media state: %#v", asset)
	}
}
