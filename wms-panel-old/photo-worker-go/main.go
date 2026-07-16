package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/h2non/bimg"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	amqp "github.com/rabbitmq/amqp091-go"
)

type photoProcessingTaskMessage struct {
	PhotoID         string `json:"photoId"`
	Bucket          string `json:"bucket"`
	IncomingObject  string `json:"incomingObjectKey"`
	FamilyRootKey   string `json:"familyRootKey"`
	ContentType     string `json:"contentType"`
	RentalItemID    string `json:"rentalItemId"`
	EventID         string `json:"eventId"`
	WarehouseCode   string `json:"warehouseCode"`
	ItemNumber      string `json:"itemNumber"`
	EventType       string `json:"eventType"`
	PreviewLongEdge int    `json:"previewLongEdge"`
	ThumbLongEdge   int    `json:"thumbLongEdge"`
	TinyLongEdge    int    `json:"tinyLongEdge"`
}

type photoProcessingResultMessage struct {
	PhotoID         string `json:"photoId"`
	Status          string `json:"status"`
	OriginalObject  string `json:"originalObjectKey,omitempty"`
	OriginalWidth   *int   `json:"originalWidth,omitempty"`
	OriginalHeight  *int   `json:"originalHeight,omitempty"`
	PreviewWidth    *int   `json:"previewWidth,omitempty"`
	PreviewHeight   *int   `json:"previewHeight,omitempty"`
	ThumbWidth      *int   `json:"thumbWidth,omitempty"`
	ThumbHeight     *int   `json:"thumbHeight,omitempty"`
	TinyWidth       *int   `json:"tinyWidth,omitempty"`
	TinyHeight      *int   `json:"tinyHeight,omitempty"`
	Error           string `json:"error,omitempty"`
}

type config struct {
	RabbitURL       string
	RequestQueue    string
	ResultQueue     string
	MinioEndpoint   string
	MinioAccessKey  string
	MinioSecretKey  string
	MinioBucket     string
	PreviewLongEdge int
	ThumbLongEdge   int
	TinyLongEdge    int
}

type worker struct {
	cfg        config
	minioClient *minio.Client
}

func main() {
	cfg := loadConfig()

	go startHealthServer()

	minioClient, err := newMinioClient(cfg)
	if err != nil {
		log.Fatalf("minio client: %v", err)
	}

	conn, err := amqp.Dial(cfg.RabbitURL)
	if err != nil {
		log.Fatalf("rabbit connection: %v", err)
	}
	defer conn.Close()

	channel, err := conn.Channel()
	if err != nil {
		log.Fatalf("rabbit channel: %v", err)
	}
	defer channel.Close()

	if err := declareQueues(channel, cfg); err != nil {
		log.Fatalf("declare queues: %v", err)
	}

	if err := channel.Qos(1, 0, false); err != nil {
		log.Fatalf("rabbit qos: %v", err)
	}

	consumerTag := "photo-worker-go"
	deliveries, err := channel.Consume(
		cfg.RequestQueue,
		consumerTag,
		false,
		false,
		false,
		false,
		nil,
	)
	if err != nil {
		log.Fatalf("consume requests: %v", err)
	}

	log.Printf("photo worker started, requestQueue=%s resultQueue=%s bucket=%s", cfg.RequestQueue, cfg.ResultQueue, cfg.MinioBucket)
	w := &worker{cfg: cfg, minioClient: minioClient}

	for delivery := range deliveries {
		if err := w.handleDelivery(channel, delivery); err != nil {
			log.Printf("delivery error: %v", err)
			_ = delivery.Nack(false, true)
			continue
		}
		_ = delivery.Ack(false)
	}
}

func loadConfig() config {
	return config{
		RabbitURL:       getenv("PHOTO_RABBITMQ_URL", "amqp://guest:guest@localhost:5672/"),
		RequestQueue:    getenv("PHOTO_PROCESS_QUEUE", "repair.media.process"),
		ResultQueue:     getenv("PHOTO_RESULT_QUEUE", "repair.media.processed"),
		MinioEndpoint:   getenv("PHOTO_MINIO_ENDPOINT", "localhost:9000"),
		MinioAccessKey:  getenv("PHOTO_MINIO_ACCESS_KEY", "minioadmin"),
		MinioSecretKey:  getenv("PHOTO_MINIO_SECRET_KEY", "minioadmin"),
		MinioBucket:     getenv("PHOTO_MINIO_BUCKET", "repair-media"),
		PreviewLongEdge: getenvInt("PHOTO_PREVIEW_LONG_EDGE", 1280),
		ThumbLongEdge:   getenvInt("PHOTO_THUMB_LONG_EDGE", 320),
		TinyLongEdge:    getenvInt("PHOTO_TINY_LONG_EDGE", 96),
	}
}

func newMinioClient(cfg config) (*minio.Client, error) {
	endpoint := normalizeEndpoint(cfg.MinioEndpoint)
	secure := strings.HasPrefix(strings.ToLower(cfg.MinioEndpoint), "https://")
	return minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinioAccessKey, cfg.MinioSecretKey, ""),
		Secure: secure,
	})
}

func declareQueues(channel *amqp.Channel, cfg config) error {
	if _, err := channel.QueueDeclare(cfg.RequestQueue, true, false, false, false, nil); err != nil {
		return err
	}
	if _, err := channel.QueueDeclare(cfg.ResultQueue, true, false, false, false, nil); err != nil {
		return err
	}
	return nil
}

func (w *worker) handleDelivery(channel *amqp.Channel, delivery amqp.Delivery) error {
	var task photoProcessingTaskMessage
	if err := json.Unmarshal(delivery.Body, &task); err != nil {
		log.Printf("skip invalid message: %v", err)
		return nil
	}
	if strings.TrimSpace(task.PhotoID) == "" {
		log.Printf("skip message without photoId")
		return nil
	}

	result := w.process(task)
	return w.publishResult(channel, result)
}

func (w *worker) process(task photoProcessingTaskMessage) photoProcessingResultMessage {
	sourceBytes, err := w.readObject(task.Bucket, task.IncomingObject)
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("read source: %v", err))
	}

	familyRoot := strings.TrimSpace(task.FamilyRootKey)
	if familyRoot == "" {
		familyRoot = path.Dir(task.IncomingObject)
	}

	originalBytes, err := resizeWithBimg(sourceBytes, bimg.Options{
		Type:          bimg.JPEG,
		Quality:       92,
		StripMetadata: true,
		NoAutoRotate:  false,
	})
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("build original: %v", err))
	}

	originalSize, err := bimg.NewImage(originalBytes).Size()
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("read original size: %v", err))
	}

	result := photoProcessingResultMessage{
		PhotoID:        task.PhotoID,
		Status:         "READY",
		OriginalObject: path.Join(familyRoot, "original.jpg"),
		OriginalWidth:  intPtr(originalSize.Width),
		OriginalHeight: intPtr(originalSize.Height),
	}

	if err := w.putObject(task.Bucket, result.OriginalObject, originalBytes, "image/jpeg"); err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("upload original: %v", err))
	}

	previewMeta, err := buildVariant(originalBytes, effectiveCap(task.PreviewLongEdge, w.cfg.PreviewLongEdge), 82)
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("build preview: %v", err))
	}
	if err := w.putObject(task.Bucket, path.Join(familyRoot, fmt.Sprintf("preview_%d.webp", previewMeta.longEdge)), previewMeta.bytes, "image/webp"); err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("upload preview: %v", err))
	}
	result.PreviewWidth = intPtr(previewMeta.width)
	result.PreviewHeight = intPtr(previewMeta.height)

	thumbMeta, err := buildVariant(originalBytes, effectiveCap(task.ThumbLongEdge, w.cfg.ThumbLongEdge), 80)
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("build thumb: %v", err))
	}
	if err := w.putObject(task.Bucket, path.Join(familyRoot, fmt.Sprintf("thumb_%d.webp", thumbMeta.longEdge)), thumbMeta.bytes, "image/webp"); err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("upload thumb: %v", err))
	}
	result.ThumbWidth = intPtr(thumbMeta.width)
	result.ThumbHeight = intPtr(thumbMeta.height)

	tinyMeta, err := buildVariant(originalBytes, effectiveCap(task.TinyLongEdge, w.cfg.TinyLongEdge), 78)
	if err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("build tiny: %v", err))
	}
	if err := w.putObject(task.Bucket, path.Join(familyRoot, fmt.Sprintf("tiny_%d.webp", tinyMeta.longEdge)), tinyMeta.bytes, "image/webp"); err != nil {
		return failedResult(task.PhotoID, fmt.Sprintf("upload tiny: %v", err))
	}
	result.TinyWidth = intPtr(tinyMeta.width)
	result.TinyHeight = intPtr(tinyMeta.height)

	return result
}

func (w *worker) publishResult(channel *amqp.Channel, result photoProcessingResultMessage) error {
	payload, err := json.Marshal(result)
	if err != nil {
		return err
	}
	return channel.PublishWithContext(
		context.Background(),
		"",
		w.cfg.ResultQueue,
		false,
		false,
		amqp.Publishing{
			ContentType: "application/json",
			Body:        payload,
			Timestamp:   time.Now(),
		},
	)
}

func (w *worker) readObject(bucket string, objectKey string) ([]byte, error) {
	object, err := w.minioClient.GetObject(context.Background(), bucket, objectKey, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer object.Close()

	return io.ReadAll(object)
}

func (w *worker) putObject(bucket string, objectKey string, data []byte, contentType string) error {
	_, err := w.minioClient.PutObject(
		context.Background(),
		bucket,
		objectKey,
		bytes.NewReader(data),
		int64(len(data)),
		minio.PutObjectOptions{ContentType: contentType},
	)
	return err
}

func resizeWithBimg(source []byte, opts bimg.Options) ([]byte, error) {
	image := bimg.NewImage(source)
	return image.Process(opts)
}

type variantMeta struct {
	bytes    []byte
	width    int
	height   int
	longEdge int
}

func buildVariant(source []byte, capLongEdge int, quality int) (variantMeta, error) {
	size, err := bimg.NewImage(source).Size()
	if err != nil {
		return variantMeta{}, err
	}
	targetWidth, targetHeight := scaleToLongEdge(size.Width, size.Height, capLongEdge)
	options := bimg.Options{
		Type:          bimg.WEBP,
		Width:         targetWidth,
		Height:        targetHeight,
		Quality:       quality,
		StripMetadata: true,
		Enlarge:       false,
	}
	bytes, err := resizeWithBimg(source, options)
	if err != nil {
		return variantMeta{}, err
	}
	processedSize, err := bimg.NewImage(bytes).Size()
	if err != nil {
		return variantMeta{}, err
	}
	return variantMeta{
		bytes:    bytes,
		width:    processedSize.Width,
		height:   processedSize.Height,
		longEdge: max(processedSize.Width, processedSize.Height),
	}, nil
}

func scaleToLongEdge(width int, height int, capLongEdge int) (int, int) {
	if width <= 0 || height <= 0 {
		return 0, 0
	}
	if capLongEdge <= 0 {
		return width, height
	}
	longEdge := max(width, height)
	if longEdge <= capLongEdge {
		return width, height
	}
	scale := float64(capLongEdge) / float64(longEdge)
	targetWidth := int(math.Round(float64(width) * scale))
	targetHeight := int(math.Round(float64(height) * scale))
	if targetWidth < 1 {
		targetWidth = 1
	}
	if targetHeight < 1 {
		targetHeight = 1
	}
	return targetWidth, targetHeight
}

func max(left int, right int) int {
	if left > right {
		return left
	}
	return right
}

func failedResult(photoID string, err string) photoProcessingResultMessage {
	return photoProcessingResultMessage{
		PhotoID: photoID,
		Status:  "FAILED",
		Error:   err,
	}
}

func effectiveCap(messageCap int, defaultCap int) int {
	if messageCap > 0 {
		return messageCap
	}
	return defaultCap
}

func intPtr(value int) *int {
	v := value
	return &v
}

func getenv(key string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getenvInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func normalizeEndpoint(value string) string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return trimmed
	}
	if parsed, err := url.Parse(trimmed); err == nil && parsed.Host != "" {
		return parsed.Host
	}
	return strings.TrimPrefix(strings.TrimPrefix(trimmed, "http://"), "https://")
}

func startHealthServer() {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/plain; charset=utf-8")
		writer.WriteHeader(http.StatusOK)
		_, _ = writer.Write([]byte("ok"))
	})
	server := &http.Server{
		Addr:              ":8081",
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Printf("health server stopped: %v", err)
	}
}
