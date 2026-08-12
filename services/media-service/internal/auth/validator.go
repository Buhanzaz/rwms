// Package auth validates RWMS bearer JWTs and turns their constrained claims
// into principals that the media boundary can authorize.
package auth

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

var (
	// ErrUnauthorized indicates that the caller did not present a valid bearer
	// token or that token validation could not safely complete.
	ErrUnauthorized = errors.New("unauthorized")
	// ErrForbidden indicates that a valid principal lacks the requested media
	// operation, scope, warehouse grant, or service identity.
	ErrForbidden = errors.New("forbidden")
)

// AccessLevel is the ordered warehouse permission level embedded in a USER
// principal's warehouse grants.
type AccessLevel int

const (
	// View permits read-only access to a warehouse-scoped media operation.
	View AccessLevel = iota
	// Edit permits ordinary mutable media operations in a warehouse.
	Edit
	// Manage permits the highest warehouse-scoped permission level.
	Manage
)

// WarehouseGrant binds a USER principal to one warehouse and its permission
// level.
type WarehouseGrant struct {
	WarehouseID uuid.UUID
	Level       AccessLevel
}

// Principal is the validated USER token representation accepted by public
// media routes.
type Principal struct {
	SubjectID uuid.UUID
	Scopes    map[string]struct{}
	Role      string
	Grants    []WarehouseGrant
}

// WorkerPrincipal is deliberately separate from Principal. A worker token is
// not a reduced user token: it has no warehouse-grant list and it must never
// inherit USER routes or permissions by accident. The media API only admits
// this principal for one task-board work-result scope: worker.tasks or
// driver.tasks, but never both.
type WorkerPrincipal struct {
	// SubjectID is the auth-service subject that owns a refresh-token/session
	// lineage. WorkerID is the task-board worker identity used for every owner
	// proof and emitted media actor reference.
	SubjectID   uuid.UUID
	WorkerID    uuid.UUID
	WarehouseID uuid.UUID
	Scopes      map[string]struct{}
}

// ServicePrincipal is intentionally distinct from a user principal: a service
// token has no user ID, warehouse grants, or global role.
type ServicePrincipal struct {
	Subject  string
	ClientID string
	Scopes   map[string]struct{}
}

// Validator verifies RS256 JWTs against the configured issuer, audience, and
// cached JWKS signing keys.
type Validator struct {
	issuer    string
	audience  string
	jwksURL   string
	client    *http.Client
	now       func() time.Time
	mu        sync.RWMutex
	refreshMu sync.Mutex
	keys      map[string]*rsa.PublicKey
	expires   time.Time
}

// NewValidator creates a JWT validator for one issuer, audience, and JWKS
// endpoint. It rejects incomplete trust configuration.
func NewValidator(issuer, audience, jwksURL string) (*Validator, error) {
	if strings.TrimSpace(issuer) == "" || strings.TrimSpace(audience) == "" || strings.TrimSpace(jwksURL) == "" {
		return nil, fmt.Errorf("JWT issuer, audience, and JWKS URL are required")
	}
	return &Validator{
		issuer:   issuer,
		audience: audience,
		jwksURL:  jwksURL,
		client:   &http.Client{Timeout: 5 * time.Second},
		now:      time.Now,
		keys:     map[string]*rsa.PublicKey{},
	}, nil
}

// Validate verifies a bearer token and returns a USER principal with its
// scopes, global role, and warehouse grants.
func (validator *Validator) Validate(ctx context.Context, authorization string) (Principal, error) {
	claims, err := validator.validateClaims(ctx, authorization)
	if err != nil {
		return Principal{}, err
	}
	if claims["principal_type"] != "USER" {
		return Principal{}, ErrForbidden
	}
	subjectRaw, ok := claims["sub"].(string)
	if !ok {
		return Principal{}, ErrForbidden
	}
	subject, err := uuid.Parse(subjectRaw)
	if err != nil {
		return Principal{}, ErrForbidden
	}
	scopes, err := parseScopes(claims)
	if err != nil {
		return Principal{}, ErrForbidden
	}
	grants, err := parseGrants(claims["warehouse_access"])
	if err != nil {
		return Principal{}, ErrForbidden
	}
	role, _ := claims["global_role"].(string)
	return Principal{SubjectID: subject, Scopes: scopes, Role: role, Grants: grants}, nil
}

// ValidateWorker verifies a bearer token and returns the deliberately limited
// WORKER principal accepted only by task-board media routes. Exactly one of
// worker.tasks and driver.tasks must be present.
func (validator *Validator) ValidateWorker(ctx context.Context, authorization string) (WorkerPrincipal, error) {
	claims, err := validator.validateClaims(ctx, authorization)
	if err != nil {
		return WorkerPrincipal{}, err
	}
	if claims["principal_type"] != "WORKER" {
		return WorkerPrincipal{}, ErrForbidden
	}
	subjectRaw, subjectOK := claims["sub"].(string)
	workerRaw, workerOK := claims["worker_id"].(string)
	warehouseRaw, warehouseOK := claims["warehouse_id"].(string)
	if !subjectOK || !workerOK || !warehouseOK {
		return WorkerPrincipal{}, ErrForbidden
	}
	subjectID, subjectErr := uuid.Parse(subjectRaw)
	workerID, workerErr := uuid.Parse(workerRaw)
	warehouseID, warehouseErr := uuid.Parse(warehouseRaw)
	if subjectErr != nil || workerErr != nil || warehouseErr != nil ||
		subjectID == uuid.Nil || workerID == uuid.Nil || warehouseID == uuid.Nil ||
		subjectID.String() != subjectRaw || workerID.String() != workerRaw ||
		warehouseID.String() != warehouseRaw {
		return WorkerPrincipal{}, ErrForbidden
	}
	scopes, err := parseScopes(claims)
	if err != nil {
		return WorkerPrincipal{}, ErrForbidden
	}
	if !hasExactlyOneTaskScope(scopes) {
		return WorkerPrincipal{}, ErrForbidden
	}
	return WorkerPrincipal{
		SubjectID: subjectID, WorkerID: workerID, WarehouseID: warehouseID,
		Scopes: scopes,
	}, nil
}

// RequireTaskAccess confirms the worker token's sole warehouse and exactly one
// of worker.tasks or driver.tasks before a task-board media operation proceeds.
func (principal WorkerPrincipal) RequireTaskAccess(warehouseID uuid.UUID) error {
	if warehouseID == uuid.Nil || warehouseID != principal.WarehouseID {
		return ErrForbidden
	}
	if !hasExactlyOneTaskScope(principal.Scopes) {
		return ErrForbidden
	}
	return nil
}

func hasExactlyOneTaskScope(scopes map[string]struct{}) bool {
	_, worker := scopes["worker.tasks"]
	_, driver := scopes["driver.tasks"]
	return worker != driver
}

// ValidateService verifies a bearer token and returns a single-scope SERVICE
// principal for a private media operation.
func (validator *Validator) ValidateService(ctx context.Context, authorization string) (ServicePrincipal, error) {
	claims, err := validator.validateClaims(ctx, authorization)
	if err != nil {
		return ServicePrincipal{}, err
	}
	if claims["principal_type"] != "SERVICE" {
		return ServicePrincipal{}, ErrForbidden
	}
	subject, subjectOK := claims["sub"].(string)
	clientID, clientOK := claims["client_id"].(string)
	if !subjectOK || !clientOK || strings.TrimSpace(subject) == "" || strings.TrimSpace(clientID) == "" ||
		len(subject) > 128 || len(clientID) > 128 || subject != clientID {
		return ServicePrincipal{}, ErrForbidden
	}
	scopes, err := parseScopes(claims)
	if err != nil || len(scopes) != 1 {
		return ServicePrincipal{}, ErrForbidden
	}
	return ServicePrincipal{Subject: subject, ClientID: clientID, Scopes: scopes}, nil
}

// RequireExact requires an exact service subject/client ID and its one allowed
// scope, preventing a broader token from reaching a private route.
func (principal ServicePrincipal) RequireExact(clientID, scope string) error {
	if principal.Subject != clientID || principal.ClientID != clientID || len(principal.Scopes) != 1 {
		return ErrForbidden
	}
	if _, ok := principal.Scopes[scope]; !ok {
		return ErrForbidden
	}
	return nil
}

func (validator *Validator) validateClaims(ctx context.Context, authorization string) (jwt.MapClaims, error) {
	const prefix = "Bearer "
	if !strings.HasPrefix(authorization, prefix) || strings.TrimSpace(strings.TrimPrefix(authorization, prefix)) == "" {
		return nil, ErrUnauthorized
	}
	raw := strings.TrimSpace(strings.TrimPrefix(authorization, prefix))
	if len(raw) > 16*1024 {
		return nil, ErrUnauthorized
	}
	claims := jwt.MapClaims{}
	token, err := jwt.ParseWithClaims(raw, claims, func(token *jwt.Token) (any, error) {
		if token.Method.Alg() != jwt.SigningMethodRS256.Alg() {
			return nil, ErrUnauthorized
		}
		kid, ok := token.Header["kid"].(string)
		if !ok || strings.TrimSpace(kid) == "" || len(kid) > 128 {
			return nil, ErrUnauthorized
		}
		return validator.key(ctx, kid)
	}, jwt.WithIssuer(validator.issuer), jwt.WithAudience(validator.audience), jwt.WithExpirationRequired(), jwt.WithLeeway(15*time.Second))
	if err != nil || token == nil || !token.Valid {
		return nil, ErrUnauthorized
	}
	return claims, nil
}

// Require verifies the requested USER scope and warehouse permission level;
// global WMS administrators are authorized without an individual grant.
func (principal Principal) Require(scope string, warehouseID uuid.UUID, level AccessLevel) error {
	if _, ok := principal.Scopes[scope]; !ok {
		return ErrForbidden
	}
	if principal.Role == "SYSTEM_ADMIN" || principal.Role == "WMS_ADMIN" {
		return nil
	}
	for _, grant := range principal.Grants {
		if grant.WarehouseID == warehouseID && grant.Level >= level {
			return nil
		}
	}
	return ErrForbidden
}

func (validator *Validator) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	validator.mu.RLock()
	key, present := validator.keys[kid]
	fresh := validator.now().Before(validator.expires)
	validator.mu.RUnlock()
	if present && fresh {
		return key, nil
	}
	if !present && fresh {
		return nil, ErrUnauthorized
	}
	if err := validator.refresh(ctx); err != nil {
		return nil, ErrUnauthorized
	}
	validator.mu.RLock()
	defer validator.mu.RUnlock()
	key, present = validator.keys[kid]
	if !present {
		return nil, ErrUnauthorized
	}
	return key, nil
}

func (validator *Validator) refresh(ctx context.Context) error {
	validator.refreshMu.Lock()
	defer validator.refreshMu.Unlock()
	validator.mu.RLock()
	fresh := validator.now().Before(validator.expires)
	validator.mu.RUnlock()
	if fresh {
		return nil
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, validator.jwksURL, nil)
	if err != nil {
		return err
	}
	response, err := validator.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("JWKS returned %d", response.StatusCode)
	}
	if response.ContentLength > 1<<20 {
		return fmt.Errorf("JWKS response is too large")
	}
	var document struct {
		Keys []struct {
			Kty string `json:"kty"`
			Use string `json:"use"`
			Alg string `json:"alg"`
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, (1<<20)+1))
	if err := decoder.Decode(&document); err != nil {
		return err
	}
	if len(document.Keys) == 0 || len(document.Keys) > 32 {
		return fmt.Errorf("JWKS key count is invalid")
	}
	keys := make(map[string]*rsa.PublicKey)
	for _, candidate := range document.Keys {
		if candidate.Kty != "RSA" || candidate.Alg != "RS256" || candidate.Use != "sig" || candidate.Kid == "" || len(candidate.Kid) > 128 || len(candidate.N) > 16*1024 || len(candidate.E) > 16 {
			continue
		}
		modulus, err := base64.RawURLEncoding.DecodeString(candidate.N)
		if err != nil {
			continue
		}
		exponentBytes, err := base64.RawURLEncoding.DecodeString(candidate.E)
		if err != nil || len(exponentBytes) == 0 || len(exponentBytes) > 4 {
			continue
		}
		exponent := 0
		for _, value := range exponentBytes {
			exponent = exponent<<8 + int(value)
		}
		modulusInteger := new(big.Int).SetBytes(modulus)
		if exponent < 3 || modulusInteger.BitLen() < 2048 || modulusInteger.BitLen() > 8192 {
			continue
		}
		keys[candidate.Kid] = &rsa.PublicKey{N: modulusInteger, E: exponent}
	}
	if len(keys) == 0 {
		return fmt.Errorf("JWKS has no usable signing keys")
	}
	validator.mu.Lock()
	validator.keys = keys
	validator.expires = validator.now().Add(5 * time.Minute)
	validator.mu.Unlock()
	return nil
}

func parseScopes(claims jwt.MapClaims) (map[string]struct{}, error) {
	raw, ok := claims["scope"]
	if !ok {
		raw = claims["scp"]
	}
	result := make(map[string]struct{})
	add := func(scope string) error {
		scope = strings.TrimSpace(scope)
		if scope == "" {
			return ErrForbidden
		}
		if _, duplicate := result[scope]; duplicate {
			return ErrForbidden
		}
		result[scope] = struct{}{}
		return nil
	}
	switch value := raw.(type) {
	case string:
		for _, scope := range strings.Fields(value) {
			if err := add(scope); err != nil {
				return nil, err
			}
		}
	case []any:
		for _, item := range value {
			scope, ok := item.(string)
			if !ok {
				return nil, ErrForbidden
			}
			if err := add(scope); err != nil {
				return nil, err
			}
		}
	default:
		return nil, ErrForbidden
	}
	return result, nil
}

func parseGrants(raw any) ([]WarehouseGrant, error) {
	entries, ok := raw.([]any)
	if !ok {
		return nil, ErrForbidden
	}
	grants := make([]WarehouseGrant, 0, len(entries))
	for _, candidate := range entries {
		entry, ok := candidate.(map[string]any)
		if !ok {
			return nil, ErrForbidden
		}
		warehouseRaw, idOK := entry["warehouseId"].(string)
		levelRaw, levelOK := entry["level"].(string)
		if !idOK || !levelOK {
			return nil, ErrForbidden
		}
		warehouseID, err := uuid.Parse(warehouseRaw)
		if err != nil {
			return nil, ErrForbidden
		}
		level, ok := map[string]AccessLevel{"VIEW": View, "EDIT": Edit, "MANAGE": Manage}[levelRaw]
		if !ok {
			return nil, ErrForbidden
		}
		grants = append(grants, WarehouseGrant{WarehouseID: warehouseID, Level: level})
	}
	return grants, nil
}
