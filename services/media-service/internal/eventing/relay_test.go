package eventing

import (
	"testing"
)

func TestProducerRequiresBrokers(t *testing.T) {
	producer, err := NewProducer([]string{"127.0.0.1:9092"})
	if err != nil {
		t.Fatalf("NewProducer() error = %v", err)
	}
	producer.Close()
}
