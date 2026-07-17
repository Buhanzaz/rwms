package media

import "time"

// ProcessingLimits are explicit runtime configuration. Zero never means a
// production default.
type ProcessingLimits struct {
	MaxImageBytes       int64
	MaxImageOutputBytes int64
	MaxDecodedPixels    int64
	MaxVideoBytes       int64
	MaxVideoOutputBytes int64
	Timeout             time.Duration
}

func (limits ProcessingLimits) Valid() bool {
	return limits.MaxImageBytes > 0 &&
		limits.MaxImageOutputBytes > 0 &&
		limits.MaxDecodedPixels > 0 &&
		limits.MaxVideoBytes > 0 &&
		limits.MaxVideoOutputBytes > 0 &&
		limits.Timeout > 0
}
