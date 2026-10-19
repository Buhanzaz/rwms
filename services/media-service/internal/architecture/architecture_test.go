// Package architecture enforces the media-service source and dependency
// boundaries independently from its runtime tests.
package architecture

import (
	"bufio"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

const rwmsModulePrefix = "dev.buhanzaz.rwms/"

// productionPackage is the parsed architecture-relevant view of one package
// that contributes non-test Go source to the media-service module.
type productionPackage struct {
	relativeDir string
	packageName string
	imports     map[string]struct{}
	hasMain     bool
}

// TestMediaServiceRemainsSingleGoDeployable prevents another executable or
// nested module from silently splitting the media ownership boundary.
func TestMediaServiceRemainsSingleGoDeployable(t *testing.T) {
	root := mediaModuleRoot(t)
	modulePath := readModulePath(t, root)
	packages := loadProductionPackages(t, root, modulePath)

	moduleFiles := make([]string, 0, 1)
	err := filepath.WalkDir(root, func(filePath string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() && sourceDirectoryIsIgnored(entry.Name()) && filePath != root {
			return filepath.SkipDir
		}
		if !entry.IsDir() && entry.Name() == "go.mod" {
			relative, err := filepath.Rel(root, filePath)
			if err != nil {
				return err
			}
			moduleFiles = append(moduleFiles, filepath.ToSlash(relative))
		}
		return nil
	})
	if err != nil {
		t.Fatalf("scan media module files: %v", err)
	}
	sort.Strings(moduleFiles)
	if len(moduleFiles) != 1 || moduleFiles[0] != "go.mod" {
		t.Fatalf("media-service must contain exactly its root go.mod, found %v", moduleFiles)
	}

	mainPackages := make([]string, 0, 1)
	for _, parsedPackage := range packages {
		if parsedPackage.packageName == "main" {
			mainPackages = append(mainPackages, parsedPackage.relativeDir)
		}
	}
	sort.Strings(mainPackages)
	if len(mainPackages) != 1 || mainPackages[0] != "cmd/media-service" {
		t.Fatalf("media-service must expose only cmd/media-service as package main, found %v", mainPackages)
	}
	command := packages[modulePath+"/cmd/media-service"]
	if command == nil || !command.hasMain {
		t.Fatal("cmd/media-service must retain a top-level main function")
	}
}

// TestProductionImportsRespectMediaOwnership builds the current production
// import graph and enforces service locality, acyclicity, and role direction.
func TestProductionImportsRespectMediaOwnership(t *testing.T) {
	root := mediaModuleRoot(t)
	modulePath := readModulePath(t, root)
	packages := loadProductionPackages(t, root, modulePath)
	packagePaths := sortedPackagePaths(packages)
	graph := make(map[string][]string, len(packages))

	for _, sourcePath := range packagePaths {
		source := packages[sourcePath]
		sourceRole, sourceLayer, sourceKnown := productionRole(source.relativeDir)
		if !sourceKnown {
			t.Errorf("production package %q has no reviewed architecture role; classify it after an ownership review", source.relativeDir)
		}

		imports := sortedImports(source.imports)
		for _, importedPath := range imports {
			if strings.HasPrefix(importedPath, rwmsModulePrefix) && !isModuleLocalImport(importedPath, modulePath) {
				t.Errorf("%s imports foreign RWMS implementation %q; services may share contracts, not source or persistence", source.relativeDir, importedPath)
				continue
			}
			if !isModuleLocalImport(importedPath, modulePath) {
				continue
			}
			target := packages[importedPath]
			if target == nil {
				t.Errorf("%s imports module-local package %q without production source", source.relativeDir, importedPath)
				continue
			}
			graph[sourcePath] = append(graph[sourcePath], importedPath)

			targetRole, targetLayer, targetKnown := productionRole(target.relativeDir)
			if !sourceKnown || !targetKnown {
				continue
			}
			if targetRole == "test-support" && sourceRole != "test-support" {
				t.Errorf("runtime package %s imports test support %s", source.relativeDir, target.relativeDir)
			}
			if sourceLayer < targetLayer {
				t.Errorf("owner-direction violation: %s (%s) imports %s (%s)", source.relativeDir, sourceRole, target.relativeDir, targetRole)
			}
			if sourceRole == "delivery" && targetRole == "delivery" &&
				deliveryOwner(source.relativeDir) != deliveryOwner(target.relativeDir) {
				t.Errorf("delivery adapters must not own each other: %s imports %s", source.relativeDir, target.relativeDir)
			}
			if packageWithin(source.relativeDir, "internal/observability") &&
				!packageWithin(target.relativeDir, "internal/observability") &&
				!packageWithin(target.relativeDir, "internal/worker") {
				t.Errorf("observability may consume only its own code and the typed worker snapshot, but %s imports %s", source.relativeDir, target.relativeDir)
			}
		}
	}

	if cycle := firstImportCycle(graph); len(cycle) != 0 {
		t.Errorf("media-service production import cycle: %s", strings.Join(cycle, " -> "))
	}
}

// TestOperationalBoundariesRejectPersistenceAndSensitiveTelemetry keeps HTTP,
// worker, and metrics code away from datastore clients and unsafe observations.
func TestOperationalBoundariesRejectPersistenceAndSensitiveTelemetry(t *testing.T) {
	root := mediaModuleRoot(t)
	modulePath := readModulePath(t, root)

	walkProductionFiles(t, root, func(relativePath string, fileSet *token.FileSet, file *ast.File) {
		relativeDir := filepath.ToSlash(filepath.Dir(relativePath))
		if !isOperationalBoundary(relativeDir) {
			return
		}

		for _, imported := range file.Imports {
			importedPath, err := strconv.Unquote(imported.Path.Value)
			if err != nil {
				t.Errorf("%s: decode import: %v", relativePath, err)
				continue
			}
			if isDirectPersistenceImport(importedPath) {
				t.Errorf("%s imports datastore client %q directly; use the media-owned adapter boundary", relativePath, importedPath)
			}
			if importedPath == "log" {
				t.Errorf("%s imports unstructured log; operational boundaries use fixed slog keys", relativePath)
			}
			if packageWithin(relativeDir, "internal/observability") {
				if importedPath == "os" || strings.HasPrefix(importedPath, "os/") {
					t.Errorf("%s imports %q; metrics must not read process secrets or execute commands", relativePath, importedPath)
				}
				if isModuleLocalImport(importedPath, modulePath) {
					importedRelative := strings.TrimPrefix(importedPath, modulePath+"/")
					if !packageWithin(importedRelative, "internal/observability") &&
						!packageWithin(importedRelative, "internal/worker") {
						t.Errorf("%s imports %q; metrics may consume only the typed worker snapshot", relativePath, importedPath)
					}
				}
			}
		}

		inspectTelemetryStructs(t, relativePath, fileSet, file)
		inspectStructuredLogKeys(t, relativePath, fileSet, file)
	})
}

// mediaModuleRoot locates the nearest go.mod so the gate works from package,
// module, and repository test invocations without a hard-coded checkout path.
func mediaModuleRoot(t *testing.T) string {
	t.Helper()
	current, err := os.Getwd()
	if err != nil {
		t.Fatalf("resolve working directory: %v", err)
	}
	for {
		if info, statErr := os.Stat(filepath.Join(current, "go.mod")); statErr == nil && !info.IsDir() {
			return current
		}
		parent := filepath.Dir(current)
		if parent == current {
			t.Fatal("cannot locate media-service go.mod")
		}
		current = parent
	}
}

// readModulePath reads the authoritative module identity from go.mod rather
// than duplicating it in the architecture policy.
func readModulePath(t *testing.T, root string) string {
	t.Helper()
	moduleFile, err := os.Open(filepath.Join(root, "go.mod"))
	if err != nil {
		t.Fatalf("open go.mod: %v", err)
	}
	defer moduleFile.Close()

	scanner := bufio.NewScanner(moduleFile)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) == 2 && fields[0] == "module" {
			return fields[1]
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("read go.mod: %v", err)
	}
	t.Fatal("go.mod does not declare a module path")
	return ""
}

// loadProductionPackages parses non-test Go files into a deterministic import
// graph view and records whether the command package retains its entry point.
func loadProductionPackages(t *testing.T, root, modulePath string) map[string]*productionPackage {
	t.Helper()
	packages := make(map[string]*productionPackage)
	walkProductionFiles(t, root, func(relativePath string, _ *token.FileSet, file *ast.File) {
		relativeDir := filepath.ToSlash(filepath.Dir(relativePath))
		if relativeDir == "." {
			relativeDir = ""
		}
		importPath := modulePath
		if relativeDir != "" {
			importPath += "/" + relativeDir
		}
		parsedPackage := packages[importPath]
		if parsedPackage == nil {
			parsedPackage = &productionPackage{
				relativeDir: relativeDir,
				packageName: file.Name.Name,
				imports:     make(map[string]struct{}),
			}
			packages[importPath] = parsedPackage
		} else if parsedPackage.packageName != file.Name.Name {
			t.Errorf("%s mixes package %s with %s", relativeDir, parsedPackage.packageName, file.Name.Name)
		}
		for _, imported := range file.Imports {
			importedPath, err := strconv.Unquote(imported.Path.Value)
			if err != nil {
				t.Errorf("%s: decode import: %v", relativePath, err)
				continue
			}
			parsedPackage.imports[importedPath] = struct{}{}
		}
		for _, declaration := range file.Decls {
			function, ok := declaration.(*ast.FuncDecl)
			if ok && function.Recv == nil && function.Name.Name == "main" {
				parsedPackage.hasMain = true
			}
		}
	})
	return packages
}

// walkProductionFiles parses each non-test source file while excluding only
// generated build output, vendored code, and VCS metadata.
func walkProductionFiles(
	t *testing.T,
	root string,
	visit func(relativePath string, fileSet *token.FileSet, file *ast.File),
) {
	t.Helper()
	err := filepath.WalkDir(root, func(filePath string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() && sourceDirectoryIsIgnored(entry.Name()) && filePath != root {
			return filepath.SkipDir
		}
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".go" || strings.HasSuffix(entry.Name(), "_test.go") {
			return nil
		}
		relativePath, err := filepath.Rel(root, filePath)
		if err != nil {
			return err
		}
		fileSet := token.NewFileSet()
		file, err := parser.ParseFile(fileSet, filePath, nil, parser.SkipObjectResolution)
		if err != nil {
			return err
		}
		visit(filepath.ToSlash(relativePath), fileSet, file)
		return nil
	})
	if err != nil {
		t.Fatalf("parse production Go sources: %v", err)
	}
}

// sourceDirectoryIsIgnored identifies directories that are not owned source.
func sourceDirectoryIsIgnored(name string) bool {
	return name == ".git" || name == "build" || name == "vendor"
}

// productionRole maps reviewed package families to semantic ownership layers.
// A new family deliberately has no default and must receive an explicit review.
func productionRole(relativeDir string) (string, int, bool) {
	switch {
	case packageWithin(relativeDir, "db/migration"),
		packageWithin(relativeDir, "internal/media"),
		packageWithin(relativeDir, "internal/auth"),
		packageWithin(relativeDir, "internal/config"),
		packageWithin(relativeDir, "internal/realtime"):
		return "foundation", 0, true
	case packageWithin(relativeDir, "internal/assetimport"),
		packageWithin(relativeDir, "internal/storage"):
		return "capability", 1, true
	case packageWithin(relativeDir, "internal/persistence"):
		return "persistence", 2, true
	case packageWithin(relativeDir, "internal/api"),
		packageWithin(relativeDir, "internal/worker"),
		packageWithin(relativeDir, "internal/eventing"):
		return "delivery", 3, true
	case packageWithin(relativeDir, "internal/observability"):
		return "observability", 4, true
	case packageWithin(relativeDir, "cmd/media-service"):
		return "composition", 5, true
	case packageWithin(relativeDir, "internal/testsupport"):
		return "test-support", 6, true
	default:
		return "", 0, false
	}
}

// deliveryOwner returns the independently owned delivery adapter family.
func deliveryOwner(relativeDir string) string {
	for _, owner := range []string{"internal/api", "internal/worker", "internal/eventing"} {
		if packageWithin(relativeDir, owner) {
			return owner
		}
	}
	return ""
}

// packageWithin reports whether a package is a reviewed root or its subpackage.
func packageWithin(relativeDir, packageRoot string) bool {
	return relativeDir == packageRoot || strings.HasPrefix(relativeDir, packageRoot+"/")
}

// isModuleLocalImport distinguishes the module itself from similarly prefixed
// module names such as media-service-tools.
func isModuleLocalImport(importedPath, modulePath string) bool {
	return importedPath == modulePath || strings.HasPrefix(importedPath, modulePath+"/")
}

// sortedPackagePaths makes map-backed diagnostics stable across test runs.
func sortedPackagePaths(packages map[string]*productionPackage) []string {
	paths := make([]string, 0, len(packages))
	for importPath := range packages {
		paths = append(paths, importPath)
	}
	sort.Strings(paths)
	return paths
}

// sortedImports makes a package's import traversal deterministic.
func sortedImports(imports map[string]struct{}) []string {
	paths := make([]string, 0, len(imports))
	for importedPath := range imports {
		paths = append(paths, importedPath)
	}
	sort.Strings(paths)
	return paths
}

// firstImportCycle returns one deterministic closed path, or nil for a DAG.
func firstImportCycle(graph map[string][]string) []string {
	states := make(map[string]uint8, len(graph))
	stack := make([]string, 0, len(graph))
	var cycle []string
	var visit func(string) bool
	visit = func(node string) bool {
		states[node] = 1
		stack = append(stack, node)
		dependencies := append([]string(nil), graph[node]...)
		sort.Strings(dependencies)
		for _, dependency := range dependencies {
			switch states[dependency] {
			case 0:
				if visit(dependency) {
					return true
				}
			case 1:
				start := 0
				for stack[start] != dependency {
					start++
				}
				cycle = append(append([]string(nil), stack[start:]...), dependency)
				return true
			}
		}
		stack = stack[:len(stack)-1]
		states[node] = 2
		return false
	}

	nodes := make([]string, 0, len(graph))
	for node := range graph {
		nodes = append(nodes, node)
	}
	sort.Strings(nodes)
	for _, node := range nodes {
		if states[node] == 0 && visit(node) {
			return cycle
		}
	}
	return nil
}

// isOperationalBoundary selects only the public API, worker, and management
// observation packages named by this policy.
func isOperationalBoundary(relativeDir string) bool {
	return packageWithin(relativeDir, "internal/api") ||
		packageWithin(relativeDir, "internal/worker") ||
		packageWithin(relativeDir, "internal/observability")
}

// isDirectPersistenceImport recognizes datastore and object-store clients that
// must remain behind media-owned persistence or storage adapters.
func isDirectPersistenceImport(importedPath string) bool {
	for _, forbidden := range []string{
		"database/sql",
		"github.com/jackc/pgx",
		"github.com/lib/pq",
		"github.com/minio/minio-go",
		"github.com/redis/go-redis",
		"go.mongodb.org/mongo-driver",
		"gorm.io/",
	} {
		if importedPath == strings.TrimSuffix(forbidden, "/") || strings.HasPrefix(importedPath, forbidden+"/") ||
			(strings.HasSuffix(forbidden, "/") && strings.HasPrefix(importedPath, forbidden)) {
			return true
		}
	}
	return false
}

// inspectTelemetryStructs rejects fields and types that could carry domain
// identity, payload bytes, credentials, object coordinates, or raw failures.
func inspectTelemetryStructs(t *testing.T, relativePath string, fileSet *token.FileSet, file *ast.File) {
	t.Helper()
	inspectAllStructs := packageWithin(filepath.ToSlash(filepath.Dir(relativePath)), "internal/observability")
	for _, declaration := range file.Decls {
		general, ok := declaration.(*ast.GenDecl)
		if !ok || general.Tok != token.TYPE {
			continue
		}
		for _, specification := range general.Specs {
			typeSpec, ok := specification.(*ast.TypeSpec)
			if !ok || (!inspectAllStructs && !strings.HasSuffix(typeSpec.Name.Name, "Telemetry")) {
				continue
			}
			structure, ok := typeSpec.Type.(*ast.StructType)
			if !ok {
				continue
			}
			for _, field := range structure.Fields.List {
				position := fileSet.Position(field.Pos())
				if len(field.Names) == 0 {
					t.Errorf("%s:%d telemetry struct %s embeds an unreviewed field", relativePath, position.Line, typeSpec.Name.Name)
					continue
				}
				if field.Tag != nil {
					t.Errorf("%s:%d telemetry struct %s must not declare transport tags", relativePath, position.Line, typeSpec.Name.Name)
				}
				for _, name := range field.Names {
					if category := forbiddenTelemetryName(name.Name); category != "" {
						t.Errorf("%s:%d telemetry field %s.%s carries forbidden %s data", relativePath, position.Line, typeSpec.Name.Name, name.Name, category)
					}
				}
				if category := forbiddenTelemetryType(field.Type); category != "" {
					t.Errorf("%s:%d telemetry struct %s carries forbidden %s type", relativePath, position.Line, typeSpec.Name.Name, category)
				}
			}
		}
	}
}

// forbiddenTelemetryName maps field and log-key names to fixed sensitive
// categories while leaving bounded status, count, age, attempt, and offset data.
func forbiddenTelemetryName(name string) string {
	normalized := strings.NewReplacer("_", "", "-", "", ".", "").Replace(strings.ToLower(name))
	categories := map[string][]string{
		"credential": {"token", "secret", "credential", "password", "cookie", "authorization"},
		"identity": {
			"mediaid", "eventid", "ownerid", "warehouseid", "userid", "clientid",
			"subjectid", "aggregateid", "recordid", "jobid", "identity",
		},
		"object coordinate": {"objectkey", "objectversion", "versionid", "url", "href"},
		"payload":           {"payload", "rawmessage", "messagebody", "bodybytes", "contentbytes", "rawbytes"},
		"source detail":     {"checksum", "sha256", "hash", "topic", "errortext", "errormessage", "errorcause", "stacktrace"},
	}
	categoryNames := make([]string, 0, len(categories))
	for category := range categories {
		categoryNames = append(categoryNames, category)
	}
	sort.Strings(categoryNames)
	for _, category := range categoryNames {
		for _, token := range categories[category] {
			if strings.Contains(normalized, token) {
				return category
			}
		}
	}
	if normalized == "data" || normalized == "content" || normalized == "bytes" ||
		normalized == "detail" || normalized == "reason" || normalized == "error" {
		return "free-form detail"
	}
	return ""
}

// forbiddenTelemetryType rejects unbounded maps, opaque interfaces, raw
// errors, byte buffers, JSON messages, URLs, and UUID identity carriers.
func forbiddenTelemetryType(expression ast.Expr) string {
	switch typed := expression.(type) {
	case *ast.MapType:
		return "unbounded map"
	case *ast.InterfaceType:
		return "opaque interface"
	case *ast.Ident:
		if typed.Name == "error" {
			return "raw error"
		}
	case *ast.ArrayType:
		if element, ok := typed.Elt.(*ast.Ident); ok && element.Name == "byte" {
			return "payload bytes"
		}
		return forbiddenTelemetryType(typed.Elt)
	case *ast.StarExpr:
		return forbiddenTelemetryType(typed.X)
	case *ast.SelectorExpr:
		switch typed.Sel.Name {
		case "RawMessage":
			return "raw message"
		case "URL":
			return "URL"
		case "UUID":
			return "identity"
		}
	}
	return ""
}

// inspectStructuredLogKeys requires fixed structured keys and rejects keys
// that describe payload, credentials, domain identity, or object coordinates.
func inspectStructuredLogKeys(t *testing.T, relativePath string, fileSet *token.FileSet, file *ast.File) {
	t.Helper()
	slogNames := slogImportNames(file)
	ast.Inspect(file, func(node ast.Node) bool {
		call, ok := node.(*ast.CallExpr)
		if !ok {
			return true
		}
		selector, ok := call.Fun.(*ast.SelectorExpr)
		if !ok {
			return true
		}
		if isStructuredLogMethod(selector.Sel.Name) && isStructuredLoggerReceiver(selector.X, slogNames) {
			if len(call.Args) == 0 {
				position := fileSet.Position(call.Pos())
				t.Errorf("%s:%d structured log call is missing its fixed message", relativePath, position.Line)
				return true
			}
			message, ok := call.Args[0].(*ast.BasicLit)
			if !ok || message.Kind != token.STRING {
				position := fileSet.Position(call.Args[0].Pos())
				t.Errorf("%s:%d structured log message must be a fixed string literal", relativePath, position.Line)
			}
			inspectLogKeyValuePairs(t, relativePath, fileSet, call.Args[1:])
			return true
		}
		identifier, ok := selector.X.(*ast.Ident)
		if ok && slogNames[identifier.Name] && isSlogAttributeConstructor(selector.Sel.Name) {
			inspectSingleLogKey(t, relativePath, fileSet, call.Args)
		}
		return true
	})
}

// isStructuredLoggerReceiver limits method-name matching to slog itself or a
// receiver whose declared role is a logger, avoiding unrelated Error methods.
func isStructuredLoggerReceiver(expression ast.Expr, slogNames map[string]bool) bool {
	switch receiver := expression.(type) {
	case *ast.Ident:
		return receiver.Name == "logger" || slogNames[receiver.Name]
	case *ast.SelectorExpr:
		return receiver.Sel.Name == "logger" || receiver.Sel.Name == "Logger"
	default:
		return false
	}
}

// slogImportNames returns every local name bound to the standard slog package.
func slogImportNames(file *ast.File) map[string]bool {
	names := make(map[string]bool)
	for _, imported := range file.Imports {
		importedPath, err := strconv.Unquote(imported.Path.Value)
		if err != nil || importedPath != "log/slog" {
			continue
		}
		name := "slog"
		if imported.Name != nil {
			name = imported.Name.Name
		}
		names[name] = true
	}
	return names
}

// isStructuredLogMethod recognizes the key/value slog methods used by RWMS.
func isStructuredLogMethod(name string) bool {
	return name == "Debug" || name == "Info" || name == "Warn" || name == "Error"
}

// isSlogAttributeConstructor recognizes standard fixed-key attribute builders.
func isSlogAttributeConstructor(name string) bool {
	switch name {
	case "Any", "Bool", "Duration", "Float64", "Group", "Int", "Int64", "String", "Time", "Uint64":
		return true
	default:
		return false
	}
}

// inspectSingleLogKey validates the first argument of a slog attribute
// constructor; Group values are attributes rather than alternating pairs.
func inspectSingleLogKey(t *testing.T, relativePath string, fileSet *token.FileSet, arguments []ast.Expr) {
	t.Helper()
	if len(arguments) == 0 {
		t.Errorf("%s: slog attribute constructor is missing its fixed key", relativePath)
		return
	}
	var value ast.Expr
	if len(arguments) > 1 {
		value = arguments[1]
	}
	inspectLogKey(t, relativePath, fileSet, arguments[0], value)
}

// inspectLogKeyValuePairs validates a sequence that starts with a key and
// alternates fixed keys with values.
func inspectLogKeyValuePairs(t *testing.T, relativePath string, fileSet *token.FileSet, arguments []ast.Expr) {
	t.Helper()
	if len(arguments)%2 != 0 {
		position := token.Position{}
		if len(arguments) != 0 {
			position = fileSet.Position(arguments[0].Pos())
		}
		t.Errorf("%s:%d structured log arguments must be fixed key/value pairs", relativePath, position.Line)
		return
	}
	for index := 0; index < len(arguments); index += 2 {
		inspectLogKey(t, relativePath, fileSet, arguments[index], arguments[index+1])
	}
}

// inspectLogKey validates one fixed key and its optional associated value.
func inspectLogKey(t *testing.T, relativePath string, fileSet *token.FileSet, keyExpression, value ast.Expr) {
	t.Helper()
	keyLiteral, ok := keyExpression.(*ast.BasicLit)
	if !ok || keyLiteral.Kind != token.STRING {
		position := fileSet.Position(keyExpression.Pos())
		t.Errorf("%s:%d structured log key must be a fixed string literal", relativePath, position.Line)
		return
	}
	key, err := strconv.Unquote(keyLiteral.Value)
	if err != nil {
		position := fileSet.Position(keyLiteral.Pos())
		t.Errorf("%s:%d decode structured log key: %v", relativePath, position.Line, err)
		return
	}
	category := forbiddenTelemetryName(key)
	if key == "error" && value != nil && isSafeErrorValue(value) {
		category = ""
	}
	if category != "" {
		position := fileSet.Position(keyLiteral.Pos())
		t.Errorf("%s:%d structured log key %q exposes forbidden %s data", relativePath, position.Line, key, category)
	}
}

// isSafeErrorValue permits the API's existing closed sanitizer while raw
// errors and arbitrary error text remain forbidden log values.
func isSafeErrorValue(expression ast.Expr) bool {
	call, ok := expression.(*ast.CallExpr)
	if !ok {
		return false
	}
	identifier, ok := call.Fun.(*ast.Ident)
	return ok && identifier.Name == "safeError"
}
