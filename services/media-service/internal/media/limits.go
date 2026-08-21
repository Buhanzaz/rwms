package media

import "time"

// ProcessingLimits are explicit runtime configuration. Zero never means a
// production default.
type ProcessingLimits struct {
	MaxVideoBytes int64
	Timeout       time.Duration
}

// Valid reports whether every processing resource bound is explicit and
// positive.
func (limits ProcessingLimits) Valid() bool {
	return limits.MaxVideoBytes > 0 &&
		limits.Timeout > 0
}
