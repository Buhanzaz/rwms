// Package observability exposes bounded, process-local operational views for
// media-service without creating a public API route or retaining domain data.
package observability

import (
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/worker"
)

const (
	processingMetricsPath          = "/metrics"
	processingPartitionSeriesLimit = 64

	processingActiveJobsMetric           = "rwms_media_processing_active_jobs"
	processingPendingJobsMetric          = "rwms_media_processing_pending_jobs"
	processingRunningJobsMetric          = "rwms_media_processing_running_jobs"
	processingTerminalReviewJobsMetric   = "rwms_media_processing_terminal_review_jobs"
	processingValidationReviewJobsMetric = "rwms_media_processing_validation_review_jobs"
	processingDependencyReviewJobsMetric = "rwms_media_processing_dependency_review_jobs"
	processingExhaustedReviewJobsMetric  = "rwms_media_processing_exhausted_review_jobs"
	processingLegacyReviewJobsMetric     = "rwms_media_processing_legacy_review_jobs"
	processingOldestActiveAgeMetric      = "rwms_media_processing_oldest_active_job_age_seconds"
	processingMaximumActiveAttemptMetric = "rwms_media_processing_maximum_active_attempt_in_cycle"
	processingBreakerClosedMetric        = "rwms_media_processing_breaker_closed"
	processingBreakerOpenMetric          = "rwms_media_processing_breaker_open"
	processingBreakerHalfOpenMetric      = "rwms_media_processing_breaker_half_open"
	processingLastHandledOffsetMetric    = "rwms_media_processing_last_handled_offset"
	processingLastCommittedOffsetMetric  = "rwms_media_processing_last_committed_offset"
)

var _ worker.ProcessingRecoveryObserver = (*ProcessingMetrics)(nil)

// ProcessingMetrics converts the processing worker's typed, fixed-cardinality
// recovery snapshots into OpenMetrics text. It keeps only the current
// observation and caps partition series so a scrape cannot gain arbitrary
// label cardinality.
type ProcessingMetrics struct {
	mutex    sync.RWMutex
	snapshot processingMetricsSnapshot
}

// processingMetricsSnapshot is the sanitized, copyable state retained between
// worker observations and management scrapes.
type processingMetricsSnapshot struct {
	pendingJobs       int64
	runningJobs       int64
	validationReviews int64
	dependencyReviews int64
	exhaustedReviews  int64
	legacyReviews     int64
	oldestActiveAge   time.Duration
	maximumAttempt    int
	breakerState      processingBreakerState
	partitions        []processingPartitionMetric
}

// processingBreakerState is the fixed metric representation of the worker's
// closed breaker state vocabulary. Unknown input deliberately emits no active
// state gauge instead of becoming a caller-controlled label value.
type processingBreakerState uint8

const (
	processingBreakerUnknown processingBreakerState = iota
	processingBreakerClosed
	processingBreakerOpen
	processingBreakerHalfOpen
)

// processingPartitionMetric stores only one bounded Kafka partition's latest
// offsets. It deliberately excludes record IDs, error details, and payload.
type processingPartitionMetric struct {
	partition    int32
	handled      int64
	committed    int64
	hasCommitted bool
}

// NewProcessingMetrics creates an initially zero-valued exporter for the
// canonical media-processing worker snapshot.
func NewProcessingMetrics() *ProcessingMetrics {
	return &ProcessingMetrics{}
}

// Observe replaces the scrape snapshot with allowed fields from one worker
// observation. The fixed 64-series partition policy retains the lowest
// non-negative partition IDs and discards the rest for this snapshot.
func (metrics *ProcessingMetrics) Observe(observation worker.ProcessingConsumerTelemetry) {
	snapshot := processingMetricsSnapshot{
		pendingJobs:       nonNegativeGauge(observation.Recovery.PendingJobs),
		runningJobs:       nonNegativeGauge(observation.Recovery.RunningJobs),
		validationReviews: nonNegativeGauge(observation.Recovery.PendingValidationReviews),
		dependencyReviews: nonNegativeGauge(observation.Recovery.PendingDependencyReviews),
		exhaustedReviews:  nonNegativeGauge(observation.Recovery.PendingExhaustedReviews),
		legacyReviews:     nonNegativeGauge(observation.Recovery.PendingLegacyReviews),
		oldestActiveAge:   nonNegativeDuration(observation.Recovery.OldestActiveJobAge),
		maximumAttempt:    nonNegativeAttempt(observation.Recovery.MaximumActiveAttemptInCycle),
		breakerState:      boundedBreakerState(observation.BreakerState),
		partitions:        boundedPartitions(observation.Partitions),
	}

	metrics.mutex.Lock()
	metrics.snapshot = snapshot
	metrics.mutex.Unlock()
}

// Handler returns the private management handler. It serves OpenMetrics only
// at GET or HEAD /metrics and never attaches itself to the public API server.
func (metrics *ProcessingMetrics) Handler() http.Handler {
	return http.HandlerFunc(metrics.serveHTTP)
}

// serveHTTP writes a complete OpenMetrics scrape from a stable snapshot.
func (metrics *ProcessingMetrics) serveHTTP(response http.ResponseWriter, request *http.Request) {
	if request.URL.Path != processingMetricsPath {
		http.NotFound(response, request)
		return
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		response.Header().Set("Allow", http.MethodGet+", "+http.MethodHead)
		response.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	response.Header().Set("Cache-Control", "no-store")
	response.Header().Set("Content-Type", "application/openmetrics-text; version=1.0.0; charset=utf-8")
	response.WriteHeader(http.StatusOK)
	if request.Method == http.MethodHead {
		return
	}
	_, _ = io.WriteString(response, metrics.openMetrics())
}

// openMetrics serializes one immutable snapshot without caller-controlled
// metric names, help text, labels, or label values.
func (metrics *ProcessingMetrics) openMetrics() string {
	snapshot := metrics.snapshotForScrape()
	var output strings.Builder
	output.Grow(4096)

	appendIntegerGauge(&output, processingActiveJobsMetric,
		"Current durable processing jobs in active states.", snapshot.pendingJobs+snapshot.runningJobs)
	appendIntegerGauge(&output, processingPendingJobsMetric,
		"Current durable processing jobs in the pending state.", snapshot.pendingJobs)
	appendIntegerGauge(&output, processingRunningJobsMetric,
		"Current durable processing jobs in the running state.", snapshot.runningJobs)
	appendIntegerGauge(&output, processingTerminalReviewJobsMetric,
		"Current durable terminal processing jobs awaiting review.",
		snapshot.validationReviews+snapshot.dependencyReviews+snapshot.exhaustedReviews+snapshot.legacyReviews)
	appendIntegerGauge(&output, processingValidationReviewJobsMetric,
		"Current durable validation terminal processing jobs awaiting review.", snapshot.validationReviews)
	appendIntegerGauge(&output, processingDependencyReviewJobsMetric,
		"Current durable dependency terminal processing jobs awaiting review.", snapshot.dependencyReviews)
	appendIntegerGauge(&output, processingExhaustedReviewJobsMetric,
		"Current durable exhausted-attempt processing jobs awaiting review.", snapshot.exhaustedReviews)
	appendIntegerGauge(&output, processingLegacyReviewJobsMetric,
		"Current durable legacy terminal processing jobs awaiting review.", snapshot.legacyReviews)
	appendFloatGauge(&output, processingOldestActiveAgeMetric,
		"Age in seconds of the oldest durable active processing job.", snapshot.oldestActiveAge.Seconds())
	appendIntegerGauge(&output, processingMaximumActiveAttemptMetric,
		"Maximum attempt in the current durable active processing cycle.", int64(snapshot.maximumAttempt))
	breakerClosed := breakerGauge(snapshot.breakerState, processingBreakerClosed)
	breakerOpen := breakerGauge(snapshot.breakerState, processingBreakerOpen)
	breakerHalfOpen := breakerGauge(snapshot.breakerState, processingBreakerHalfOpen)
	appendIntegerGauge(&output, processingBreakerClosedMetric,
		"Whether the processing dependency circuit breaker is closed.", breakerClosed)
	appendIntegerGauge(&output, processingBreakerOpenMetric,
		"Whether the processing dependency circuit breaker is open.", breakerOpen)
	appendIntegerGauge(&output, processingBreakerHalfOpenMetric,
		"Whether the processing dependency circuit breaker is half open.", breakerHalfOpen)
	appendPartitionOffsetGauge(&output, processingLastHandledOffsetMetric,
		"Last durably handled media-processing Kafka offset by bounded partition.", snapshot.partitions, false)
	appendPartitionOffsetGauge(&output, processingLastCommittedOffsetMetric,
		"Last acknowledged media-processing Kafka offset by bounded partition.", snapshot.partitions, true)
	output.WriteString("# EOF\n")
	return output.String()
}

// snapshotForScrape copies the mutable partition slice while holding the read
// lock, allowing response writes to proceed independently from Observe.
func (metrics *ProcessingMetrics) snapshotForScrape() processingMetricsSnapshot {
	metrics.mutex.RLock()
	snapshot := metrics.snapshot
	snapshot.partitions = append([]processingPartitionMetric(nil), metrics.snapshot.partitions...)
	metrics.mutex.RUnlock()
	return snapshot
}

// boundedPartitions applies the fixed exporter cardinality policy without
// allocating a map from untrusted partition input.
func boundedPartitions(observations []worker.ProcessingPartitionTelemetry) []processingPartitionMetric {
	selected := make([]processingPartitionMetric, 0, processingPartitionSeriesLimit)
	for _, observation := range observations {
		if observation.Partition < 0 {
			continue
		}
		candidate := processingPartitionMetric{
			partition:    observation.Partition,
			handled:      observation.LastHandledOffset,
			committed:    observation.LastCommittedOffset,
			hasCommitted: observation.HasCommittedOffset,
		}
		index := sort.Search(len(selected), func(index int) bool {
			return selected[index].partition >= candidate.partition
		})
		if index < len(selected) && selected[index].partition == candidate.partition {
			selected[index] = candidate
			continue
		}
		if len(selected) == processingPartitionSeriesLimit {
			if index == len(selected) {
				continue
			}
			copy(selected[index+1:], selected[index:len(selected)-1])
			selected[index] = candidate
			continue
		}
		selected = append(selected, processingPartitionMetric{})
		copy(selected[index+1:], selected[index:len(selected)-1])
		selected[index] = candidate
	}
	return selected
}

// appendIntegerGauge writes a complete unlabelled OpenMetrics gauge family.
func appendIntegerGauge(output *strings.Builder, name, help string, value int64) {
	appendGaugeDefinition(output, name, help)
	output.WriteString(name)
	output.WriteByte(' ')
	output.WriteString(strconv.FormatInt(value, 10))
	output.WriteByte('\n')
}

// appendFloatGauge writes a complete unlabelled OpenMetrics gauge family.
func appendFloatGauge(output *strings.Builder, name, help string, value float64) {
	appendGaugeDefinition(output, name, help)
	output.WriteString(name)
	output.WriteByte(' ')
	output.WriteString(strconv.FormatFloat(value, 'g', -1, 64))
	output.WriteByte('\n')
}

// appendPartitionOffsetGauge writes only numeric partition labels selected by
// boundedPartitions; it omits unavailable committed offsets rather than
// inventing a value.
func appendPartitionOffsetGauge(
	output *strings.Builder,
	name string,
	help string,
	partitions []processingPartitionMetric,
	committed bool,
) {
	appendGaugeDefinition(output, name, help)
	for _, partition := range partitions {
		if committed && !partition.hasCommitted {
			continue
		}
		value := partition.handled
		if committed {
			value = partition.committed
		}
		output.WriteString(name)
		output.WriteString("{partition=\"")
		output.WriteString(strconv.FormatInt(int64(partition.partition), 10))
		output.WriteString("\"} ")
		output.WriteString(strconv.FormatInt(value, 10))
		output.WriteByte('\n')
	}
}

// appendGaugeDefinition writes fixed valid OpenMetrics metadata for one gauge.
func appendGaugeDefinition(output *strings.Builder, name, help string) {
	output.WriteString("# HELP ")
	output.WriteString(name)
	output.WriteByte(' ')
	output.WriteString(help)
	output.WriteByte('\n')
	output.WriteString("# TYPE ")
	output.WriteString(name)
	output.WriteString(" gauge\n")
}

// nonNegativeGauge protects the exposition from impossible negative database
// counters without retaining a malformed value.
func nonNegativeGauge(value int64) int64 {
	if value < 0 {
		return 0
	}
	return value
}

// nonNegativeDuration protects the age gauge from a malformed negative value.
func nonNegativeDuration(value time.Duration) time.Duration {
	if value < 0 {
		return 0
	}
	return value
}

// nonNegativeAttempt protects the bounded attempt gauge from malformed input.
func nonNegativeAttempt(value int) int {
	if value < 0 {
		return 0
	}
	return value
}

// boundedBreakerState maps the worker's closed vocabulary without retaining an
// unknown source string in the retained scrape state.
func boundedBreakerState(value string) processingBreakerState {
	switch value {
	case "CLOSED":
		return processingBreakerClosed
	case "OPEN":
		return processingBreakerOpen
	case "HALF_OPEN":
		return processingBreakerHalfOpen
	default:
		return processingBreakerUnknown
	}
}

// breakerGauge returns one only for the selected fixed breaker state.
func breakerGauge(state, selected processingBreakerState) int64 {
	if state == selected {
		return 1
	}
	return 0
}
