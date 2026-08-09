package observability

import (
	"mime"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/worker"
)

func TestProcessingMetricsExportsBoundedOpenMetricsSnapshot(t *testing.T) {
	metrics := NewProcessingMetrics()
	metrics.Observe(worker.ProcessingConsumerTelemetry{
		Recovery: persistence.ProcessingRecoveryTelemetry{
			PendingJobs:                 2,
			RunningJobs:                 3,
			PendingValidationReviews:    5,
			PendingDependencyReviews:    7,
			PendingExhaustedReviews:     11,
			PendingLegacyReviews:        13,
			OldestActiveJobAge:          90 * time.Second,
			MaximumActiveAttemptInCycle: 4,
		},
		BreakerState: "HALF_OPEN",
		Partitions: []worker.ProcessingPartitionTelemetry{
			{Partition: 9, LastHandledOffset: 101, LastCommittedOffset: 100, HasCommittedOffset: true},
			{Partition: 3, LastHandledOffset: 41},
		},
	})

	response := scrapeProcessingMetrics(t, metrics)
	mediaType, parameters, err := mime.ParseMediaType(response.Header().Get("Content-Type"))
	if err != nil || mediaType != "application/openmetrics-text" || parameters["version"] != "1.0.0" {
		t.Fatalf("content type = %q, parameters = %#v, error = %v", mediaType, parameters, err)
	}
	body := response.Body.String()
	assertValidOpenMetrics(t, body)
	for _, expected := range []string{
		"rwms_media_processing_active_jobs 5\n",
		"rwms_media_processing_terminal_review_jobs 36\n",
		"rwms_media_processing_oldest_active_job_age_seconds 90\n",
		"rwms_media_processing_maximum_active_attempt_in_cycle 4\n",
		"rwms_media_processing_breaker_closed 0\n",
		"rwms_media_processing_breaker_open 0\n",
		"rwms_media_processing_breaker_half_open 1\n",
		"rwms_media_processing_last_handled_offset{partition=\"3\"} 41\n",
		"rwms_media_processing_last_handled_offset{partition=\"9\"} 101\n",
		"rwms_media_processing_last_committed_offset{partition=\"9\"} 100\n",
	} {
		if !strings.Contains(body, expected) {
			t.Fatalf("OpenMetrics body does not contain %q:\n%s", expected, body)
		}
	}
	if strings.Contains(body, "rwms_media_processing_last_committed_offset{partition=\"3\"}") {
		t.Fatalf("body invented unavailable committed offset:\n%s", body)
	}
	for _, forbidden := range []string{
		"123e4567-e89b-12d3-a456-426614174000",
		"secret@example.test",
		"mediaId",
		"eventId",
		"userId",
		"payload",
		"error=",
		"topic=",
	} {
		if strings.Contains(body, forbidden) {
			t.Fatalf("OpenMetrics body leaked forbidden value %q:\n%s", forbidden, body)
		}
	}
}

func TestProcessingMetricsBoundsPartitionSeriesAndUnknownBreakerState(t *testing.T) {
	metrics := NewProcessingMetrics()
	partitions := make([]worker.ProcessingPartitionTelemetry, 0, processingPartitionSeriesLimit+3)
	for partition := processingPartitionSeriesLimit + 2; partition >= 0; partition-- {
		partitions = append(partitions, worker.ProcessingPartitionTelemetry{
			Partition: int32(partition), LastHandledOffset: int64(partition),
			LastCommittedOffset: int64(partition), HasCommittedOffset: true,
		})
	}
	partitions = append(partitions, worker.ProcessingPartitionTelemetry{Partition: -1, LastHandledOffset: 999})
	metrics.Observe(worker.ProcessingConsumerTelemetry{
		BreakerState: "UNKNOWN_CALLER_VALUE",
		Partitions:   partitions,
	})

	body := scrapeProcessingMetrics(t, metrics).Body.String()
	if count := strings.Count(body, processingLastHandledOffsetMetric+"{partition="); count != processingPartitionSeriesLimit {
		t.Fatalf("handled partition series = %d, want %d:\n%s", count, processingPartitionSeriesLimit, body)
	}
	if strings.Contains(body, "partition=\"66\"") || !strings.Contains(body, "partition=\"0\"") {
		t.Fatalf("partition cardinality policy did not retain only lowest IDs:\n%s", body)
	}
	for _, metric := range []string{
		processingBreakerClosedMetric,
		processingBreakerOpenMetric,
		processingBreakerHalfOpenMetric,
	} {
		if !strings.Contains(body, metric+" 0\n") {
			t.Fatalf("unknown breaker state was not fail-safe for %s:\n%s", metric, body)
		}
	}
	if strings.Contains(body, "UNKNOWN_CALLER_VALUE") {
		t.Fatalf("unknown breaker state leaked into scrape:\n%s", body)
	}
}

func TestProcessingMetricsObserveAndScrapeConcurrently(t *testing.T) {
	metrics := NewProcessingMetrics()
	const workers = 8
	const scrapesPerWorker = 50
	errors := make(chan error, workers)
	var group sync.WaitGroup
	for workerIndex := 0; workerIndex < workers; workerIndex++ {
		group.Add(1)
		go func(workerIndex int) {
			defer group.Done()
			for scrapeIndex := 0; scrapeIndex < scrapesPerWorker; scrapeIndex++ {
				metrics.Observe(worker.ProcessingConsumerTelemetry{
					Recovery:     persistence.ProcessingRecoveryTelemetry{PendingJobs: int64(workerIndex + scrapeIndex)},
					BreakerState: []string{"CLOSED", "OPEN", "HALF_OPEN"}[scrapeIndex%3],
					Partitions: []worker.ProcessingPartitionTelemetry{{
						Partition: int32(workerIndex), LastHandledOffset: int64(scrapeIndex),
					}},
				})
				request := httptest.NewRequest(http.MethodGet, processingMetricsPath, nil)
				response := httptest.NewRecorder()
				metrics.Handler().ServeHTTP(response, request)
				if response.Code != http.StatusOK || !strings.HasSuffix(response.Body.String(), "# EOF\n") {
					errors <- strconv.ErrSyntax
					return
				}
			}
		}(workerIndex)
	}
	group.Wait()
	close(errors)
	for err := range errors {
		t.Fatalf("concurrent Observe/Scrape failed: %v", err)
	}
}

func scrapeProcessingMetrics(t *testing.T, metrics *ProcessingMetrics) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, processingMetricsPath, nil)
	response := httptest.NewRecorder()
	metrics.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("scrape status = %d, body = %s", response.Code, response.Body.String())
	}
	return response
}

func assertValidOpenMetrics(t *testing.T, body string) {
	t.Helper()
	lines := strings.Split(strings.TrimSuffix(body, "\n"), "\n")
	if len(lines) == 0 || lines[len(lines)-1] != "# EOF" {
		t.Fatalf("OpenMetrics output does not end with EOF:\n%s", body)
	}
	metricName := regexp.MustCompile(`^[a-zA-Z_:][a-zA-Z0-9_:]*$`)
	partitionSample := regexp.MustCompile(`^([a-zA-Z_:][a-zA-Z0-9_:]*)\{partition="[0-9]+"\}$`)
	for _, line := range lines[:len(lines)-1] {
		if strings.HasPrefix(line, "# HELP ") || strings.HasPrefix(line, "# TYPE ") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) != 2 {
			t.Fatalf("invalid OpenMetrics sample %q", line)
		}
		if strings.Contains(fields[0], "{") {
			if !partitionSample.MatchString(fields[0]) {
				t.Fatalf("invalid bounded label sample %q", line)
			}
		} else if !metricName.MatchString(fields[0]) {
			t.Fatalf("invalid metric name in %q", line)
		}
		if _, err := strconv.ParseFloat(fields[1], 64); err != nil {
			t.Fatalf("invalid metric value in %q: %v", line, err)
		}
	}
}
