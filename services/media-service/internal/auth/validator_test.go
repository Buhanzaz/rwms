package auth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const testKeyID = "media-test-key"

func TestValidatorAcceptsValidRS256UserToken(t *testing.T) {
	fixture := newJWTFixture(t)
	warehouseID := uuid.New()
	claims := fixture.validClaims(warehouseID)

	principal, err := fixture.validator.Validate(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
	if err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if principal.SubjectID.String() != claims["sub"] {
		t.Fatalf("SubjectID = %s, want %s", principal.SubjectID, claims["sub"])
	}
	if err := principal.Require("rwms.read", warehouseID, View); err != nil {
		t.Fatalf("Require(read, view) error = %v", err)
	}
	if err := principal.Require("rwms.write", warehouseID, Edit); err != nil {
		t.Fatalf("Require(write, edit) error = %v", err)
	}
	if got := fixture.requests.Load(); got != 1 {
		t.Fatalf("JWKS requests = %d, want 1", got)
	}
}

func TestValidatorAcceptsOnlyExactCustomerRentalIdentity(t *testing.T) {
	fixture := newJWTFixture(t)
	claims := fixture.validClaims(uuid.New())
	claims["global_role"] = "CUSTOMER"
	claims["client_id"] = "rwms-customer-android"
	claims["scope"] = "customer.rental"

	principal, err := fixture.validator.Validate(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
	if err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if !principal.IsCustomerRental() || !principal.IsCustomerIdentity() {
		t.Fatalf("customer principal = %#v", principal)
	}

	for _, mutate := range []func(Principal) Principal{
		func(value Principal) Principal { value.Role = "MANAGER"; return value },
		func(value Principal) Principal { value.ClientID = "manager-android"; return value },
		func(value Principal) Principal {
			value.Scopes = map[string]struct{}{"customer.rental": {}, "rwms.read": {}}
			return value
		},
	} {
		changed := mutate(principal)
		if changed.IsCustomerRental() || !changed.IsCustomerIdentity() {
			t.Fatalf("non-exact customer identity accepted = %#v", changed)
		}
	}
}

func TestValidatorRejectsMalformedUserClientID(t *testing.T) {
	fixture := newJWTFixture(t)
	claims := fixture.validClaims(uuid.New())
	claims["client_id"] = 42
	_, err := fixture.validator.Validate(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("Validate() error = %v, want forbidden", err)
	}
}

func TestValidatorAcceptsNarrowWorkerTaskToken(t *testing.T) {
	for _, taskScope := range []string{"worker.tasks", "driver.tasks"} {
		t.Run(taskScope, func(t *testing.T) {
			fixture := newJWTFixture(t)
			warehouseID := uuid.New()
			workerID := uuid.New()
			claims := fixture.validWorkerClaims(warehouseID, workerID)
			claims["scope"] = "openid profile " + taskScope

			principal, err := fixture.validator.ValidateWorker(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
			if err != nil {
				t.Fatalf("ValidateWorker() error = %v", err)
			}
			if principal.WorkerID != workerID || principal.WarehouseID != warehouseID {
				t.Fatalf("worker principal = %#v", principal)
			}
			if err := principal.RequireTaskAccess(warehouseID); err != nil {
				t.Fatalf("RequireTaskAccess() error = %v", err)
			}
			if err := principal.RequireTaskAccess(uuid.New()); !errors.Is(err, ErrForbidden) {
				t.Fatalf("cross-warehouse RequireTaskAccess() error = %v, want forbidden", err)
			}
		})
	}
}

func TestValidatorRejectsWorkerTokenWithoutExactWorkerClaims(t *testing.T) {
	fixture := newJWTFixture(t)
	warehouseID := uuid.New()
	workerID := uuid.New()
	tests := []struct {
		name   string
		mutate func(jwt.MapClaims)
	}{
		{name: "missing worker scope", mutate: func(claims jwt.MapClaims) { claims["scope"] = "openid profile" }},
		{name: "both task scopes", mutate: func(claims jwt.MapClaims) { claims["scope"] = "worker.tasks driver.tasks" }},
		{name: "user principal", mutate: func(claims jwt.MapClaims) { claims["principal_type"] = "USER" }},
		{name: "noncanonical worker id", mutate: func(claims jwt.MapClaims) { claims["worker_id"] = strings.ToUpper(workerID.String()) }},
		{name: "missing warehouse", mutate: func(claims jwt.MapClaims) { delete(claims, "warehouse_id") }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			claims := fixture.validWorkerClaims(warehouseID, workerID)
			test.mutate(claims)
			_, err := fixture.validator.ValidateWorker(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
			if !errors.Is(err, ErrForbidden) {
				t.Fatalf("ValidateWorker() error = %v, want forbidden", err)
			}
		})
	}
}

func TestWorkerPrincipalRejectsAmbiguousTaskScopes(t *testing.T) {
	warehouseID := uuid.New()
	principal := WorkerPrincipal{
		WarehouseID: warehouseID,
		Scopes: map[string]struct{}{
			"worker.tasks": {},
			"driver.tasks": {},
		},
	}

	if err := principal.RequireTaskAccess(warehouseID); !errors.Is(err, ErrForbidden) {
		t.Fatalf("RequireTaskAccess() error = %v, want forbidden", err)
	}
}

func TestValidatorAcceptsExactLogisticsServiceToken(t *testing.T) {
	fixture := newJWTFixture(t)
	claims := fixture.validServiceClaims("media.logistics")

	principal, err := fixture.validator.ValidateService(context.Background(), "Bearer "+fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256))
	if err != nil {
		t.Fatalf("ValidateService() error = %v", err)
	}
	if err := principal.RequireExact("logistics-service", "media.logistics"); err != nil {
		t.Fatalf("RequireExact() error = %v", err)
	}
	if got := fixture.requests.Load(); got != 1 {
		t.Fatalf("JWKS requests = %d, want 1", got)
	}
}

func TestValidatorRejectsInvalidLogisticsServiceToken(t *testing.T) {
	fixture := newJWTFixture(t)
	now := time.Now()
	tests := []struct {
		name     string
		claims   func() jwt.MapClaims
		mutate   func(jwt.MapClaims)
		want     error
		validate func(auth string) error
	}{
		{
			name:   "user principal",
			claims: func() jwt.MapClaims { return fixture.validClaims(uuid.New()) },
			want:   ErrForbidden,
		},
		{
			name:   "wrong client",
			claims: func() jwt.MapClaims { return fixture.validServiceClaims("media.logistics") },
			mutate: func(claims jwt.MapClaims) { claims["client_id"] = "warehouse-service" },
			want:   ErrForbidden,
		},
		{
			name:   "combined scopes",
			claims: func() jwt.MapClaims { return fixture.validServiceClaims("media.logistics") },
			mutate: func(claims jwt.MapClaims) { claims["scope"] = "media.logistics asset.logistics" },
			want:   ErrForbidden,
		},
		{
			name:   "duplicate scopes",
			claims: func() jwt.MapClaims { return fixture.validServiceClaims("media.logistics") },
			mutate: func(claims jwt.MapClaims) { claims["scope"] = "media.logistics media.logistics" },
			want:   ErrForbidden,
		},
		{
			name:   "foreign scope",
			claims: func() jwt.MapClaims { return fixture.validServiceClaims("asset.logistics") },
			want:   nil,
			validate: func(authorization string) error {
				principal, err := fixture.validator.ValidateService(context.Background(), authorization)
				if err != nil {
					return err
				}
				return principal.RequireExact("logistics-service", "media.logistics")
			},
		},
		{
			name:   "expired",
			claims: func() jwt.MapClaims { return fixture.validServiceClaims("media.logistics") },
			mutate: func(claims jwt.MapClaims) { claims["exp"] = now.Add(-time.Minute).Unix() },
			want:   ErrUnauthorized,
		},
		{
			name:     "missing bearer",
			claims:   func() jwt.MapClaims { return fixture.validServiceClaims("media.logistics") },
			want:     ErrUnauthorized,
			validate: func(string) error { _, err := fixture.validator.ValidateService(context.Background(), ""); return err },
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			claims := test.claims()
			if test.mutate != nil {
				test.mutate(claims)
			}
			authorization := "Bearer " + fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256)
			validate := test.validate
			if validate == nil {
				validate = func(value string) error {
					_, err := fixture.validator.ValidateService(context.Background(), value)
					return err
				}
			}
			err := validate(authorization)
			if test.want == nil {
				if !errors.Is(err, ErrForbidden) {
					t.Fatalf("service authorization error = %v, want %v", err, ErrForbidden)
				}
				return
			}
			if !errors.Is(err, test.want) {
				t.Fatalf("ValidateService() error = %v, want %v", err, test.want)
			}
		})
	}
}

func TestValidatorRejectsInvalidTokenClaimsAndAlgorithm(t *testing.T) {
	fixture := newJWTFixture(t)
	warehouseID := uuid.New()
	now := time.Now()

	tests := []struct {
		name       string
		mutate     func(jwt.MapClaims)
		method     jwt.SigningMethod
		want       error
		authorizer func() string
	}{
		{
			name:   "wrong issuer",
			mutate: func(claims jwt.MapClaims) { claims["iss"] = "https://wrong.example" },
			method: jwt.SigningMethodRS256,
			want:   ErrUnauthorized,
		},
		{
			name:   "wrong audience",
			mutate: func(claims jwt.MapClaims) { claims["aud"] = "not-rwms" },
			method: jwt.SigningMethodRS256,
			want:   ErrUnauthorized,
		},
		{
			name:   "expired",
			mutate: func(claims jwt.MapClaims) { claims["exp"] = now.Add(-time.Minute).Unix() },
			method: jwt.SigningMethodRS256,
			want:   ErrUnauthorized,
		},
		{
			name:   "not active yet",
			mutate: func(claims jwt.MapClaims) { claims["nbf"] = now.Add(time.Minute).Unix() },
			method: jwt.SigningMethodRS256,
			want:   ErrUnauthorized,
		},
		{
			name:   "service principal",
			mutate: func(claims jwt.MapClaims) { claims["principal_type"] = "SERVICE" },
			method: jwt.SigningMethodRS256,
			want:   ErrForbidden,
		},
		{
			name:   "non UUID subject",
			mutate: func(claims jwt.MapClaims) { claims["sub"] = "operator" },
			method: jwt.SigningMethodRS256,
			want:   ErrForbidden,
		},
		{
			name: "malformed warehouse id",
			mutate: func(claims jwt.MapClaims) {
				claims["warehouse_access"] = []any{map[string]any{"warehouseId": "not-a-uuid", "level": "EDIT"}}
			},
			method: jwt.SigningMethodRS256,
			want:   ErrForbidden,
		},
		{
			name: "unknown warehouse level",
			mutate: func(claims jwt.MapClaims) {
				claims["warehouse_access"] = []any{map[string]any{"warehouseId": warehouseID.String(), "level": "OWNER"}}
			},
			method: jwt.SigningMethodRS256,
			want:   ErrForbidden,
		},
		{
			name:   "missing grants",
			mutate: func(claims jwt.MapClaims) { delete(claims, "warehouse_access") },
			method: jwt.SigningMethodRS256,
			want:   ErrForbidden,
		},
		{
			name:   "non RS256 algorithm",
			mutate: func(jwt.MapClaims) {},
			method: jwt.SigningMethodPS256,
			want:   ErrUnauthorized,
		},
		{
			name:   "missing bearer token",
			mutate: func(jwt.MapClaims) {},
			method: jwt.SigningMethodRS256,
			want:   ErrUnauthorized,
			authorizer: func() string {
				return ""
			},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			claims := fixture.validClaims(warehouseID)
			test.mutate(claims)
			authorization := "Bearer " + fixture.sign(t, testKeyID, claims, test.method)
			if test.authorizer != nil {
				authorization = test.authorizer()
			}
			_, err := fixture.validator.Validate(context.Background(), authorization)
			if !errors.Is(err, test.want) {
				t.Fatalf("Validate() error = %v, want %v", err, test.want)
			}
		})
	}
}

func TestValidatorDoesNotAmplifyUnknownKeyRefreshes(t *testing.T) {
	fixture := newJWTFixture(t)
	claims := fixture.validClaims(uuid.New())
	known := fixture.sign(t, testKeyID, claims, jwt.SigningMethodRS256)
	if _, err := fixture.validator.Validate(context.Background(), "Bearer "+known); err != nil {
		t.Fatalf("prime Validate() error = %v", err)
	}
	if got := fixture.requests.Load(); got != 1 {
		t.Fatalf("JWKS requests after prime = %d, want 1", got)
	}

	unknown := fixture.sign(t, "unknown-key", claims, jwt.SigningMethodRS256)
	const attempts = 24
	var wait sync.WaitGroup
	start := make(chan struct{})
	for range attempts {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			_, err := fixture.validator.Validate(context.Background(), "Bearer "+unknown)
			if !errors.Is(err, ErrUnauthorized) {
				t.Errorf("Validate(unknown kid) error = %v, want unauthorized", err)
			}
		}()
	}
	close(start)
	wait.Wait()
	if got := fixture.requests.Load(); got != 1 {
		t.Fatalf("fresh-cache unknown kid caused %d JWKS requests, want 1 total", got)
	}

	fixture.validator.mu.Lock()
	fixture.validator.expires = time.Time{}
	fixture.validator.mu.Unlock()
	start = make(chan struct{})
	for range attempts {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			_, err := fixture.validator.Validate(context.Background(), "Bearer "+unknown)
			if !errors.Is(err, ErrUnauthorized) {
				t.Errorf("Validate(unknown kid after expiry) error = %v, want unauthorized", err)
			}
		}()
	}
	close(start)
	wait.Wait()
	if got := fixture.requests.Load(); got != 2 {
		t.Fatalf("expired-cache unknown kid caused %d JWKS requests, want one serialized refresh (2 total)", got)
	}
}

func TestMalformedWarehouseGrantFailsClosed(t *testing.T) {
	tests := []struct {
		name string
		raw  any
	}{
		{name: "mixed valid and invalid", raw: []any{
			map[string]any{"warehouseId": uuid.NewString(), "level": "EDIT"},
			map[string]any{"warehouseId": "not-a-uuid", "level": "MANAGE"},
		}},
		{name: "not an array", raw: "warehouse"},
		{name: "entry not an object", raw: []any{"warehouse"}},
		{name: "missing level", raw: []any{map[string]any{"warehouseId": uuid.NewString()}}},
		{name: "wrong level type", raw: []any{map[string]any{"warehouseId": uuid.NewString(), "level": 2}}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseGrants(test.raw); err == nil {
				t.Fatal("parseGrants() error = nil, want whole claim rejection")
			}
		})
	}
}

func TestPrincipalRequiresExactScopeAndWarehouseLevel(t *testing.T) {
	warehouseID := uuid.New()
	principal := Principal{
		SubjectID: uuid.New(),
		Scopes:    map[string]struct{}{"rwms.write": {}},
		Grants:    []WarehouseGrant{{WarehouseID: warehouseID, Level: Edit}},
	}
	if err := principal.Require("rwms.write", warehouseID, Edit); err != nil {
		t.Fatalf("Require() error = %v", err)
	}
	if err := principal.Require("rwms.read", warehouseID, View); err == nil {
		t.Fatal("Require() accepted an absent exact scope")
	}
	if err := principal.Require("rwms.write", uuid.New(), Edit); err == nil {
		t.Fatal("Require() accepted cross-warehouse access")
	}
}

type jwtFixture struct {
	privateKey *rsa.PrivateKey
	issuer     string
	audience   string
	server     *httptest.Server
	validator  *Validator
	requests   atomic.Int32
}

func newJWTFixture(t *testing.T) *jwtFixture {
	t.Helper()
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("rsa.GenerateKey() error = %v", err)
	}
	fixture := &jwtFixture{
		privateKey: privateKey,
		issuer:     "https://auth.example.test",
		audience:   "rwms-services",
	}
	fixture.server = httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		fixture.requests.Add(1)
		response.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(response).Encode(map[string]any{"keys": []any{map[string]any{
			"kty": "RSA",
			"use": "sig",
			"alg": "RS256",
			"kid": testKeyID,
			"n":   base64.RawURLEncoding.EncodeToString(privateKey.PublicKey.N.Bytes()),
			"e":   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(privateKey.PublicKey.E)).Bytes()),
		}}}); err != nil {
			t.Errorf("encode JWKS: %v", err)
		}
	}))
	t.Cleanup(fixture.server.Close)
	fixture.validator, err = NewValidator(fixture.issuer, fixture.audience, fixture.server.URL)
	if err != nil {
		t.Fatalf("NewValidator() error = %v", err)
	}
	return fixture
}

func (fixture *jwtFixture) validClaims(warehouseID uuid.UUID) jwt.MapClaims {
	now := time.Now()
	return jwt.MapClaims{
		"iss":            fixture.issuer,
		"aud":            fixture.audience,
		"sub":            uuid.NewString(),
		"iat":            now.Add(-time.Minute).Unix(),
		"nbf":            now.Add(-time.Minute).Unix(),
		"exp":            now.Add(5 * time.Minute).Unix(),
		"principal_type": "USER",
		"scope":          "rwms.read rwms.write",
		"warehouse_access": []any{map[string]any{
			"warehouseId": warehouseID.String(),
			"level":       "EDIT",
		}},
	}
}

func (fixture *jwtFixture) validServiceClaims(scope string) jwt.MapClaims {
	now := time.Now()
	return jwt.MapClaims{
		"iss":            fixture.issuer,
		"aud":            fixture.audience,
		"sub":            "logistics-service",
		"client_id":      "logistics-service",
		"iat":            now.Add(-time.Minute).Unix(),
		"nbf":            now.Add(-time.Minute).Unix(),
		"exp":            now.Add(5 * time.Minute).Unix(),
		"principal_type": "SERVICE",
		"scope":          scope,
	}
}

func (fixture *jwtFixture) validWorkerClaims(warehouseID, workerID uuid.UUID) jwt.MapClaims {
	now := time.Now()
	return jwt.MapClaims{
		"iss": fixture.issuer, "aud": fixture.audience, "sub": uuid.NewString(),
		"iat": now.Add(-time.Minute).Unix(), "nbf": now.Add(-time.Minute).Unix(), "exp": now.Add(5 * time.Minute).Unix(),
		"principal_type": "WORKER", "worker_id": workerID.String(), "warehouse_id": warehouseID.String(),
		"scope": "openid profile worker.tasks",
	}
}

func (fixture *jwtFixture) sign(t *testing.T, kid string, claims jwt.MapClaims, method jwt.SigningMethod) string {
	t.Helper()
	token := jwt.NewWithClaims(method, claims)
	token.Header["kid"] = kid
	signed, err := token.SignedString(fixture.privateKey)
	if err != nil {
		t.Fatalf("SignedString() error = %v", err)
	}
	return signed
}
