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
