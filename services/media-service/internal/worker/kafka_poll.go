package worker

import (
	"context"
	"errors"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
)

// kafkaConsumerPollTimeout bounds an otherwise idle PollFetches call.  A
// finite poll gives a BlockRebalanceOnPoll consumer a regular opportunity to
// release a blocked rebalance after a broker or coordinator interruption.
const kafkaConsumerPollTimeout = 15 * time.Second

type kafkaPollClient interface {
	PollFetches(context.Context) kgo.Fetches
}

type kafkaConsumerClient interface {
	kafkaPollClient
	CommitRecords(context.Context, ...*kgo.Record) error
	AllowRebalance()
	Close()
}

// pollKafkaFetches deliberately scopes only the wait for records. Processing
// and offset commits keep the caller context, so a received record is still
// persisted before it can be acknowledged.
func pollKafkaFetches(
	ctx context.Context,
	client kafkaPollClient,
	timeout time.Duration,
) (kgo.Fetches, bool) {
	if timeout <= 0 {
		timeout = kafkaConsumerPollTimeout
	}
	pollContext, cancel := context.WithTimeout(ctx, timeout)
	fetches := client.PollFetches(pollContext)
	pollTimedOut := ctx.Err() == nil && errors.Is(pollContext.Err(), context.DeadlineExceeded)
	cancel()
	return fetches, pollTimedOut
}
