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

func TestRotationUsesOptimisticVersionAndKeepsPreviousGenerationOnFailure(t *testing.T) {
	asset := Asset{
		ID:                "m-1",
		Status:            StatusReady,
		Version:           7,
		Rotation:          Rotation0,
		CurrentGeneration: 1,
	}

	if _, err := asset.QueueRotation(6, Rotation90); err == nil {
		t.Fatal("QueueRotation() error = nil, want optimistic version conflict")
	}

	job, err := asset.QueueRotation(7, Rotation90)
	if err != nil {
		t.Fatalf("QueueRotation() error = %v", err)
	}
	if job.Kind != ProcessingRotation || job.Generation != 2 || job.Rotation != Rotation90 {
		t.Fatalf("rotation job = %#v, want generation 2 / 90 degrees", job)
	}
	if asset.CurrentGeneration != 1 || asset.Rotation != Rotation0 || asset.Status != StatusProcessing {
		t.Fatalf("asset changed current generation before completion: %#v", asset)
	}

	if err := asset.FailProcessing(job, "ffmpeg failed"); err != nil {
		t.Fatalf("FailProcessing() error = %v", err)
	}
	if asset.Status != StatusReady || asset.CurrentGeneration != 1 || asset.Rotation != Rotation0 {
		t.Fatalf("failed rotation did not preserve current generation: %#v", asset)
	}
	if asset.ProcessingError != "ffmpeg failed" {
		t.Fatalf("processing error = %q, want ffmpeg failed", asset.ProcessingError)
	}
}

func TestRotationPublishesNewGenerationOnlyAfterMatchingJobCompletes(t *testing.T) {
	asset := Asset{
		ID:                "m-1",
		Status:            StatusReady,
		Version:           2,
		Rotation:          Rotation90,
		CurrentGeneration: 4,
	}
	job, err := asset.QueueRotation(2, Rotation270)
	if err != nil {
		t.Fatalf("QueueRotation() error = %v", err)
	}

	if err := asset.CompleteProcessing(ProcessingJob{MediaID: "m-1", Generation: 5, Rotation: Rotation180}); err == nil {
		t.Fatal("CompleteProcessing() error = nil, want mismatched job rejection")
	}
	if asset.CurrentGeneration != 4 || asset.Rotation != Rotation90 {
		t.Fatalf("mismatched job changed current media: %#v", asset)
	}

	if err := asset.CompleteProcessing(job); err != nil {
		t.Fatalf("CompleteProcessing() error = %v", err)
	}
	if asset.Status != StatusReady || asset.CurrentGeneration != 5 || asset.Rotation != Rotation270 {
		t.Fatalf("completed rotation asset = %#v", asset)
	}
}
