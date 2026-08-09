package worker

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
)

func TestConsumerAddRecoveryObserverFansOutWithStructuredLog(t *testing.T) {
	var logOutput bytes.Buffer
	consumer := NewConsumer(nil, nil, Processor{}, "test-owner", time.Second,
		slog.New(slog.NewJSONHandler(&logOutput, nil)))
	metricsObserver := &processingObserverProbe{}
	consumer.AddRecoveryObserver(metricsObserver)

	snapshot := ProcessingConsumerTelemetry{
		Recovery:     persistence.ProcessingRecoveryTelemetry{PendingJobs: 2},
		BreakerState: "CLOSED",
	}
	consumer.observer.Observe(snapshot)

	if len(metricsObserver.snapshots) != 1 || metricsObserver.snapshots[0].Recovery.PendingJobs != 2 {
		t.Fatalf("metrics observer snapshots = %#v", metricsObserver.snapshots)
	}
	if !strings.Contains(logOutput.String(), "media processing recovery telemetry") ||
		!strings.Contains(logOutput.String(), "pendingJobs") {
		t.Fatalf("structured recovery log was not retained: %s", logOutput.String())
	}
}

// processingObserverProbe retains snapshots for the fanout wiring assertion.
type processingObserverProbe struct {
	snapshots []ProcessingConsumerTelemetry
}

// Observe implements ProcessingRecoveryObserver for the focused fanout test.
func (probe *processingObserverProbe) Observe(snapshot ProcessingConsumerTelemetry) {
	probe.snapshots = append(probe.snapshots, snapshot)
}
