package main

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"time"
)

// managementMetricsRuntime owns the separately bound private management
// listener. It is intentionally independent from the public api.Server
// handler, so management metrics cannot become a public media API route.
type managementMetricsRuntime struct {
	server   *http.Server
	listener net.Listener
}

// newManagementMetricsRuntime binds the already validated loopback management
// address before supervision starts. A pre-bound listener makes startup bind
// failure explicit and gives tests a race-free ephemeral listener address.
func newManagementMetricsRuntime(address string, handler http.Handler) (*managementMetricsRuntime, error) {
	if handler == nil {
		return nil, errors.New("management metrics handler is required")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, err
	}
	return &managementMetricsRuntime{
		listener: listener,
		server: &http.Server{
			Addr:              address,
			Handler:           handler,
			ReadHeaderTimeout: 5 * time.Second,
			ReadTimeout:       10 * time.Second,
			WriteTimeout:      10 * time.Second,
			IdleTimeout:       30 * time.Second,
			MaxHeaderBytes:    8 << 10,
			ErrorLog:          log.New(io.Discard, "", 0),
		},
	}, nil
}

// Run serves management requests until Shutdown closes the private listener.
// The supervisor owns Shutdown, so a canceled process context alone cannot
// leave the listener running.
func (runtime *managementMetricsRuntime) Run(_ context.Context) error {
	err := runtime.server.Serve(runtime.listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown drains active management scrapes within the supervisor's bounded
// context and stops accepting new connections on the private listener.
func (runtime *managementMetricsRuntime) Shutdown(ctx context.Context) error {
	return runtime.server.Shutdown(ctx)
}

// Address returns the actual bound management listener address, including an
// ephemeral port selected for a focused runtime test.
func (runtime *managementMetricsRuntime) Address() string {
	return runtime.listener.Addr().String()
}
