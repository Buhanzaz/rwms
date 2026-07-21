package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"github.com/twmb/franz-go/pkg/kgo"
)

const cabinOwnerReconciliationFileLimit = 16 << 20

const (
	cabinReconciliationContiguousReplay = "CONTIGUOUS_REPLAY"
	cabinReconciliationVerifyConflict   = "VERIFY_APPLIED_CONFLICT"
)

type cabinOwnerReconciliationFile struct {
	Mode                      string `json:"mode"`
	AggregateID               string `json:"aggregateId"`
	ExpectedCheckpointVersion int64  `json:"expectedCheckpointVersion"`
	ReviewerID                string `json:"reviewerId"`
	Reason                    string `json:"reason"`
	Records                   []struct {
		Topic string          `json:"topic"`
		Key   string          `json:"key"`
		Value json.RawMessage `json:"value"`
	} `json:"records"`
}

func RunCabinOwnerReconciliationFile(
	ctx context.Context,
	repository *persistence.Repository,
	path string,
) error {
	path = strings.TrimSpace(path)
	if path == "" {
		return errors.New("cabin owner reconciliation file is required")
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 ||
		info.Size() > cabinOwnerReconciliationFileLimit {
		return errors.New("cabin owner reconciliation file is not a bounded regular file")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read cabin owner reconciliation file: %w", err)
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	var batch cabinOwnerReconciliationFile
	if err := decoder.Decode(&batch); err != nil {
		return fmt.Errorf("decode cabin owner reconciliation file: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("cabin owner reconciliation file has trailing data")
	}
	aggregateID, aggregateErr := strictUUID(batch.AggregateID)
	reviewerID, reviewerErr := strictUUID(batch.ReviewerID)
	if aggregateErr != nil || reviewerErr != nil || len(batch.Records) == 0 {
		return persistence.ErrReconciliation
	}
	messages := make([]persistence.CabinOwnerMessage, 0, len(batch.Records))
	for _, source := range batch.Records {
		message, err := parseCabinOwnerRecord(&kgo.Record{
			Topic: source.Topic, Key: []byte(source.Key), Value: source.Value,
		})
		if err != nil || message.AggregateID != aggregateID {
			return persistence.ErrReconciliation
		}
		messages = append(messages, message)
	}
	mode := strings.TrimSpace(batch.Mode)
	if mode == "" {
		mode = cabinReconciliationContiguousReplay
	}
	if mode == cabinReconciliationVerifyConflict {
		if len(messages) != 1 {
			return persistence.ErrReconciliation
		}
		return repository.ReconcileCabinOwnerConflict(ctx, aggregateID,
			batch.ExpectedCheckpointVersion, reviewerID, batch.Reason, messages[0])
	}
	if mode != cabinReconciliationContiguousReplay {
		return persistence.ErrReconciliation
	}
	return repository.ReconcileCabinOwnerAggregate(ctx, aggregateID,
		batch.ExpectedCheckpointVersion, reviewerID, batch.Reason, messages)
}
