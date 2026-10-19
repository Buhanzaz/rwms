package persistence

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/testsupport"
	"github.com/google/uuid"
)

func TestRepositoryContentAdvisoryLocksUseDistinctNamespacesAndReleaseAfterRequestCancellation(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	openContext, cancelOpen := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelOpen()
	database, err := Open(openContext, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)
	lockID := uuid.New()

	requestContext, cancelRequest := context.WithCancel(context.Background())
	releaseSession, err := repository.AcquireUploadSessionContentLock(requestContext, lockID)
	if err != nil {
		t.Fatalf("AcquireUploadSessionContentLock() error = %v", err)
	}
	variantContext, cancelVariant := context.WithTimeout(requestContext, 2*time.Second)
	defer cancelVariant()
	releaseVariant, err := repository.AcquireUploadImageVariantContentLock(variantContext, lockID, media.VariantSmall)
	if err != nil {
		_ = releaseSession()
		t.Fatalf("variant lock sharing an ID collided with session namespace: %v", err)
	}

	cancelRequest()
	if err := releaseVariant(); err != nil {
		_ = releaseSession()
		t.Fatalf("release variant after request cancellation: %v", err)
	}
	if err := releaseVariant(); err != nil {
		_ = releaseSession()
		t.Fatalf("second variant release must be idempotent: %v", err)
	}
	if err := releaseSession(); err != nil {
		t.Fatalf("release session after request cancellation: %v", err)
	}
	if err := releaseSession(); err != nil {
		t.Fatalf("second session release must be idempotent: %v", err)
	}

	reacquireContext, cancelReacquire := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancelReacquire()
	releaseReacquired, err := repository.AcquireUploadSessionContentLock(reacquireContext, lockID)
	if err != nil {
		t.Fatalf("reacquire released session lock: %v", err)
	}
	if err := releaseReacquired(); err != nil {
		t.Fatalf("release reacquired session lock: %v", err)
	}
}

func TestRepositoryContentAdvisoryLockRejectsInvalidVariantAndHonorsCanceledAcquisition(t *testing.T) {
	databaseURL := os.Getenv("MEDIA_TEST_DATABASE_URL")
	if databaseURL == "" {
		t.Skip("MEDIA_TEST_DATABASE_URL is not configured")
	}
	databaseURL = testsupport.NewMigratedMediaDatabase(t, databaseURL)
	openContext, cancelOpen := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelOpen()
	database, err := Open(openContext, databaseURL)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	repository := NewRepository(database.Pool)

	if _, err := repository.AcquireUploadImageVariantContentLock(context.Background(), uuid.Nil, media.VariantSmall); !errors.Is(err, ErrConflict) {
		t.Fatalf("nil media ID error = %v, want ErrConflict", err)
	}
	if _, err := repository.AcquireUploadImageVariantContentLock(context.Background(), uuid.New(), media.VariantOriginal); !errors.Is(err, ErrConflict) {
		t.Fatalf("original variant error = %v, want ErrConflict", err)
	}

	lockID := uuid.New()
	release, err := repository.AcquireUploadSessionContentLock(context.Background(), lockID)
	if err != nil {
		t.Fatalf("AcquireUploadSessionContentLock() error = %v", err)
	}
	blockedContext, cancelBlocked := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancelBlocked()
	if _, err := repository.AcquireUploadSessionContentLock(blockedContext, lockID); err == nil {
		_ = release()
		t.Fatal("contended lock acquisition unexpectedly succeeded")
	}
	if err := release(); err != nil {
		t.Fatalf("release held lock after contended acquisition: %v", err)
	}
}
