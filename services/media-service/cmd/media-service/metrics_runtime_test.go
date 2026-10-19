package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/observability"
)

func TestManagementMetricsRuntimeServesPrivateExporterAndShutsDown(t *testing.T) {
	metrics := observability.NewProcessingMetrics()
	runtime, err := newManagementMetricsRuntime(reserveConcreteLoopbackAddress(t), metrics.Handler())
	if err != nil {
		t.Fatalf("new management metrics runtime: %v", err)
	}
	done := runManagementMetricsRuntime(runtime)

	response := getManagementMetrics(t, runtime.Address(), "/metrics")
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil {
		t.Fatalf("read metrics response: %v", err)
	}
	if response.StatusCode != http.StatusOK || !strings.HasSuffix(string(body), "# EOF\n") {
		t.Fatalf("metrics response status=%d body=%q", response.StatusCode, body)
	}
	response = getManagementMetrics(t, runtime.Address(), "/api/media/v1/assets")
	response.Body.Close()
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("management non-metrics route status=%d, want 404", response.StatusCode)
	}

	shutdownContext, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := runtime.Shutdown(shutdownContext); err != nil {
		t.Fatalf("shutdown management metrics runtime: %v", err)
	}
	if err := awaitManagementMetricsRuntime(done); err != nil {
		t.Fatalf("management metrics runtime exit: %v", err)
	}
}

func TestManagementMetricsRuntimeBoundsShutdownWhileSlowScrapeIsActive(t *testing.T) {
	scrapeStarted := make(chan struct{})
	releaseScrape := make(chan struct{})
	runtime, err := newManagementMetricsRuntime(reserveConcreteLoopbackAddress(t), http.HandlerFunc(func(
		response http.ResponseWriter,
		request *http.Request,
	) {
		close(scrapeStarted)
		<-releaseScrape
		_, _ = response.Write([]byte("# EOF\n"))
	}))
	if err != nil {
		t.Fatalf("new management metrics runtime: %v", err)
	}
	done := runManagementMetricsRuntime(runtime)
	responseDone := make(chan error, 1)
	go func() {
		response, requestErr := http.Get("http://" + runtime.Address() + "/metrics")
		if requestErr == nil {
			_, requestErr = io.Copy(io.Discard, response.Body)
			response.Body.Close()
		}
		responseDone <- requestErr
	}()
	select {
	case <-scrapeStarted:
	case <-time.After(time.Second):
		t.Fatal("slow scrape did not reach management listener")
	}

	shutdownContext, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	err = runtime.Shutdown(shutdownContext)
	cancel()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("slow scrape shutdown error = %v, want deadline exceeded", err)
	}
	close(releaseScrape)
	select {
	case err := <-responseDone:
		if err != nil {
			t.Fatalf("slow scrape response: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("slow scrape did not finish after release")
	}
	if err := awaitManagementMetricsRuntime(done); err != nil {
		t.Fatalf("management metrics runtime exit: %v", err)
	}
}

func reserveConcreteLoopbackAddress(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve loopback address: %v", err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatalf("release reserved loopback address: %v", err)
	}
	return address
}

func runManagementMetricsRuntime(runtime *managementMetricsRuntime) <-chan error {
	done := make(chan error, 1)
	go func() {
		done <- runtime.Run(context.Background())
	}()
	return done
}

func getManagementMetrics(t *testing.T, address, path string) *http.Response {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		response, err := http.Get("http://" + address + path)
		if err == nil {
			return response
		}
		if time.Now().After(deadline) {
			t.Fatalf("GET management metrics: %v", err)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func awaitManagementMetricsRuntime(done <-chan error) error {
	select {
	case err := <-done:
		return err
	case <-time.After(time.Second):
		return errors.New("management metrics runtime did not stop")
	}
}
