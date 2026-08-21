// Command media-service runs RWMS's stateful media boundary. It owns the
// HTTP API, private versioned object storage integration, durable media facts,
// and the workers that process and reconcile media-related Kafka streams.
package main

import (
	"context"
	"errors"
	"io"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"dev.buhanzaz.rwms/media-service/internal/api"
	"dev.buhanzaz.rwms/media-service/internal/assetimport"
	"dev.buhanzaz.rwms/media-service/internal/auth"
	"dev.buhanzaz.rwms/media-service/internal/config"
	"dev.buhanzaz.rwms/media-service/internal/eventing"
	"dev.buhanzaz.rwms/media-service/internal/media"
	"dev.buhanzaz.rwms/media-service/internal/observability"
	"dev.buhanzaz.rwms/media-service/internal/persistence"
	"dev.buhanzaz.rwms/media-service/internal/storage"
	"dev.buhanzaz.rwms/media-service/internal/worker"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(logger); err != nil {
		logger.Error("media service stopped", "failureType", "RUNTIME_FAILURE", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	if len(os.Args) > 1 {
		if len(os.Args) != 3 || (os.Args[1] != "reconcile-inventory-owner" &&
			os.Args[1] != "reconcile-cabin-owner") {
			return errors.New("supported operator commands: reconcile-inventory-owner or reconcile-cabin-owner <reviewed-batch.json>")
		}
		databaseURL := os.Getenv("MEDIA_DATABASE_URL")
		if databaseURL == "" {
			return errors.New("MEDIA_DATABASE_URL is required for owner reconciliation")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		database, err := persistence.Open(ctx, databaseURL)
		if err != nil {
			return err
		}
		defer database.Close()
		repository := persistence.NewRepository(database.Pool)
		if os.Args[1] == "reconcile-cabin-owner" {
			return worker.RunCabinOwnerReconciliationFile(ctx, repository, os.Args[2])
		}
		return worker.RunInventoryOwnerReconciliationFile(ctx, repository, os.Args[2])
	}
	configuration, err := config.Load()
	if err != nil {
		return err
	}
	var videoTranscoder media.VideoTranscoder
	var videoProbe media.VideoProbe
	if configuration.MaxVideoDuration > 0 {
		ffmpeg, transcodeErr := media.NewFFmpegTranscoder(configuration.FFmpegExecutable)
		if transcodeErr != nil {
			return transcodeErr
		}
		ffprobe, probeErr := media.NewFFprobe(configuration.FFprobeExecutable)
		if probeErr != nil {
			return probeErr
		}
		videoTranscoder = ffmpeg
		videoProbe = ffprobe
	}
	startupContext, startupCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer startupCancel()
	database, err := persistence.Open(startupContext, configuration.DatabaseURL)
	if err != nil {
		return err
	}
	defer database.Close()
	objectStore, err := storage.NewMinIOObjectStore(storage.MinIOOptions{
		Endpoint: configuration.MinIOEndpoint, AccessKey: configuration.MinIOAccessKey,
		SecretKey: configuration.MinIOSecretKey, Bucket: configuration.MinIOBucket,
		UseSSL: configuration.MinIOUseSSL,
	})
	if err != nil {
		return err
	}
	if err := objectStore.EnsureVersioning(startupContext); err != nil {
		return err
	}
	validator, err := auth.NewValidator(configuration.Issuer, configuration.Audience, configuration.JWKSURL)
	if err != nil {
		return err
	}
	repository := persistence.NewRepository(database.Pool)
	assetImportService, err := assetimport.NewService(repository)
	if err != nil {
		return err
	}
	yandexClient, err := assetimport.NewYandexClient()
	if err != nil {
		return err
	}
	assetImportWorker, err := assetimport.NewWorker(repository, yandexClient, objectStore,
		configuration.InstanceID+":asset-import", configuration.MaxUploadBytes, logger)
	if err != nil {
		return err
	}
	producer, err := eventing.NewProducer(configuration.KafkaBrokers)
	if err != nil {
		return err
	}
	relay := eventing.NewRelay(repository, producer, configuration.InstanceID+":outbox", logger)
	consumerClient, err := worker.NewKafkaConsumer(configuration.KafkaBrokers, configuration.ProcessingGroup, configuration.ProcessingTopic)
	if err != nil {
		producer.Close()
		return err
	}
	ownerConsumerClient, err := worker.NewInventoryOwnerKafkaConsumer(configuration.KafkaBrokers,
		configuration.InventoryOwnerGroup, configuration.InventoryTopic)
	if err != nil {
		consumerClient.Close()
		producer.Close()
		return err
	}
	cabinOwnerConsumerClient, err := worker.NewCabinOwnerKafkaConsumer(configuration.KafkaBrokers,
		configuration.CabinOwnerGroup, configuration.AssetRentalItemTopic)
	if err != nil {
		ownerConsumerClient.Close()
		consumerClient.Close()
		producer.Close()
		return err
	}
	taskBoardOwnerProofConsumerClient, err := worker.NewTaskBoardEntryOwnerProofKafkaConsumer(
		configuration.KafkaBrokers, configuration.TaskBoardEntryOwnerProofGroup,
		configuration.TaskBoardEntryOwnerProofTopic)
	if err != nil {
		cabinOwnerConsumerClient.Close()
		ownerConsumerClient.Close()
		consumerClient.Close()
		producer.Close()
		return err
	}
	limits := media.ProcessingLimits{
		MaxVideoBytes: configuration.MaxUploadBytes,
		Timeout:       configuration.ProcessingTimeout,
	}
	processingConsumer := worker.NewConsumer(repository, consumerClient, worker.Processor{
		Video: media.VideoProcessor{
			Store: objectStore, Probe: videoProbe, Transcoder: videoTranscoder,
			AllowedCodecs: configuration.AllowedVideoCodecs, MaxDuration: configuration.MaxVideoDuration,
			MaxOutputBytes: configuration.MaxVideoOutputBytes, Limits: limits,
		},
	}, configuration.InstanceID+":worker", configuration.ProcessingTimeout, logger)
	ownerConsumer := worker.NewInventoryOwnerConsumer(repository, ownerConsumerClient, logger)
	cabinOwnerConsumer := worker.NewCabinOwnerConsumer(repository, cabinOwnerConsumerClient, logger)
	taskBoardOwnerProofConsumer := worker.NewTaskBoardEntryOwnerProofConsumer(
		repository, taskBoardOwnerProofConsumerClient, logger)
	apiServer, err := api.NewServer(repository, database, validator, objectStore, api.Configuration{
		MaxUploadBytes: configuration.MaxUploadBytes, AllowedMIMETypes: configuration.AllowedMIMETypes,
		UploadExpiry: configuration.UploadExpiry, AssetImports: assetImportService,
	}, logger)
	if err != nil {
		taskBoardOwnerProofConsumerClient.Close()
		cabinOwnerConsumerClient.Close()
		ownerConsumerClient.Close()
		consumerClient.Close()
		producer.Close()
		return err
	}
	processingMetrics := observability.NewProcessingMetrics()
	managementMetrics, err := newManagementMetricsRuntime(configuration.ManagementAddress, processingMetrics.Handler())
	if err != nil {
		taskBoardOwnerProofConsumerClient.Close()
		cabinOwnerConsumerClient.Close()
		ownerConsumerClient.Close()
		consumerClient.Close()
		producer.Close()
		return err
	}
	processingConsumer.SetInvalidationPublisher(apiServer.Invalidations())
	processingConsumer.AddRecoveryObserver(processingMetrics)
	httpServer := &http.Server{
		Addr: configuration.HTTPAddress, Handler: apiServer.Handler(),
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: configuration.HTTPReadTimeout,
		WriteTimeout: configuration.HTTPWriteTimeout, IdleTimeout: 60 * time.Second,
		MaxHeaderBytes: 32 << 10, ErrorLog: log.New(io.Discard, "", 0),
	}
	logger.Info("media service started", "address", configuration.HTTPAddress,
		"managementAddress", configuration.ManagementAddress)
	signalContext, stopSignal := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stopSignal()
	processes := []mediaRuntimeProcess{
		{name: "outbox-relay", run: relay.Run},
		{name: "processing-consumer", run: processingConsumer.Run},
		{name: "inventory-owner-consumer", run: ownerConsumer.Run},
		{name: "cabin-owner-consumer", run: cabinOwnerConsumer.Run},
		{name: "task-board-entry-owner-proof-consumer", run: taskBoardOwnerProofConsumer.Run},
		{name: "asset-import-worker", run: assetImportWorker.Run},
		{name: "http-server", run: func(context.Context) error {
			err := httpServer.ListenAndServe()
			if errors.Is(err, http.ErrServerClosed) {
				return nil
			}
			return err
		}},
		{name: "metrics-server", run: managementMetrics.Run},
	}
	err = superviseMediaRuntime(signalContext, 20*time.Second, processes,
		func(shutdownContext context.Context) error {
			var shutdownError error
			if closeErr := httpServer.Shutdown(shutdownContext); closeErr != nil {
				shutdownError = closeErr
			}
			if closeErr := managementMetrics.Shutdown(shutdownContext); closeErr != nil && shutdownError == nil {
				shutdownError = closeErr
			}
			if closeErr := processingConsumer.Close(shutdownContext); closeErr != nil && shutdownError == nil {
				shutdownError = closeErr
			}
			ownerConsumer.Close()
			cabinOwnerConsumer.Close()
			taskBoardOwnerProofConsumer.Close()
			if closeErr := relay.Close(shutdownContext); closeErr != nil && shutdownError == nil {
				shutdownError = closeErr
			}
			return shutdownError
		})
	logger.Info("media service stopped")
	return err
}

type mediaRuntimeProcess struct {
	name string
	run  func(context.Context) error
}

type mediaRuntimeResult struct {
	name string
	err  error
}

func superviseMediaRuntime(
	signalContext context.Context,
	shutdownTimeout time.Duration,
	processes []mediaRuntimeProcess,
	shutdown func(context.Context) error,
) error {
	required := map[string]bool{
		"outbox-relay": false, "processing-consumer": false,
		"inventory-owner-consumer": false, "cabin-owner-consumer": false,
		"task-board-entry-owner-proof-consumer": false,
		"asset-import-worker":                   false,
		"http-server":                           false,
		"metrics-server":                        false,
	}
	if signalContext == nil || shutdownTimeout <= 0 || shutdown == nil || len(processes) != len(required) {
		return errors.New("media runtime requires all required supervised processes")
	}
	for _, process := range processes {
		if process.run == nil {
			return errors.New("media runtime process is nil")
		}
		if _, exists := required[process.name]; !exists || required[process.name] {
			return errors.New("media runtime process set is invalid")
		}
		required[process.name] = true
	}
	runtimeContext, cancelRuntime := context.WithCancel(context.Background())
	results := make(chan mediaRuntimeResult, len(processes))
	var runtimeGroup sync.WaitGroup
	for _, process := range processes {
		process := process
		runtimeGroup.Add(1)
		go func() {
			defer runtimeGroup.Done()
			results <- mediaRuntimeResult{name: process.name, err: process.run(runtimeContext)}
		}()
	}
	var runtimeError error
	select {
	case <-signalContext.Done():
	case result := <-results:
		runtimeError = result.err
		if runtimeError == nil {
			runtimeError = errors.New("required media runtime process stopped: " + result.name)
		}
	}
	cancelRuntime()
	shutdownContext, shutdownCancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer shutdownCancel()
	if err := shutdown(shutdownContext); err != nil && runtimeError == nil {
		runtimeError = err
	}
	runtimeStopped := make(chan struct{})
	go func() {
		runtimeGroup.Wait()
		close(runtimeStopped)
	}()
	select {
	case <-runtimeStopped:
	case <-shutdownContext.Done():
		if runtimeError == nil {
			runtimeError = shutdownContext.Err()
		}
	}
	return runtimeError
}
