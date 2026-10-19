package dev.buhanzaz.rwms.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads canonical YAML and JSON sources from one real {@code contracts/} boundary without
 * following a source or reference outside that boundary.
 */
final class ContractDocumentRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final Path contractsRoot;
  private final Map<Path, ContractDocument> documents;

  private ContractDocumentRepository(Path contractsRoot, Map<Path, ContractDocument> documents) {
    this.contractsRoot = contractsRoot;
    this.documents = Map.copyOf(documents);
  }

  /**
   * Parses every active YAML and JSON contract source beneath {@code contractsRoot} before any
   * semantic verifier is allowed to inspect it.
   *
   * @param contractsRoot the canonical repository boundary
   * @return the parsed, path-bounded document repository
   */
  static ContractDocumentRepository load(Path contractsRoot) {
    Path root = realDirectory(contractsRoot);
    var documents = new LinkedHashMap<Path, ContractDocument>();
    for (Path source : sourcePaths(root)) {
      ContractDocument previous = documents.putIfAbsent(source, parse(source));
      if (previous != null) {
        throw new IllegalArgumentException(
            "Canonical contract source resolves to the same real path twice: " + source);
      }
    }
    if (documents.isEmpty()) {
      throw new IllegalArgumentException("No canonical YAML or JSON sources found under " + root);
    }
    return new ContractDocumentRepository(root, documents);
  }

  /** Returns all parsed contract sources in deterministic path order. */
  List<ContractDocument> documents() {
    return documents.values().stream().sorted(Comparator.comparing(ContractDocument::path)).toList();
  }

  /**
   * Resolves one local reference target, rejecting missing files, non-contract files and paths
   * that leave the canonical boundary including through a symbolic link.
   */
  ContractDocument document(Path candidate) {
    Path normalized = candidate.toAbsolutePath().normalize();
    if (!normalized.startsWith(contractsRoot)) {
      throw new IllegalArgumentException(
          "Canonical contract reference escapes " + contractsRoot + ": " + candidate);
    }
    if (!Files.isRegularFile(normalized)) {
      throw new IllegalArgumentException("Canonical contract reference does not exist: " + candidate);
    }
    Path realPath;
    try {
      realPath = normalized.toRealPath();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot resolve canonical contract reference: " + candidate, exception);
    }
    if (!realPath.startsWith(contractsRoot)) {
      throw new IllegalArgumentException(
          "Canonical contract reference escapes " + contractsRoot + " through a symbolic link: " + candidate);
    }
    ContractDocument document = documents.get(realPath);
    if (document == null) {
      throw new IllegalArgumentException(
          "Canonical contract reference must target an active YAML or JSON source: " + candidate);
    }
    return document;
  }

  /** Returns a canonical source path relative to the bounded {@code contracts/} directory. */
  Path relativePath(ContractDocument document) {
    return contractsRoot.relativize(document.path());
  }

  private static Path realDirectory(Path candidate) {
    try {
      Path root = candidate.toRealPath();
      if (!Files.isDirectory(root)) {
        throw new IllegalArgumentException("Canonical contracts root is not a directory: " + root);
      }
      return root;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot resolve canonical contracts root: " + candidate, exception);
    }
  }

  private static List<Path> sourcePaths(Path root) {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(ContractDocumentRepository::isContractSource)
          .map(ContractDocumentRepository::realPath)
          .peek(path -> ensureInsideRoot(root, path))
          .sorted()
          .toList();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot scan canonical contracts under " + root, exception);
    }
  }

  private static boolean isContractSource(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
  }

  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Cannot resolve canonical contract source: " + path, exception);
    }
  }

  private static void ensureInsideRoot(Path root, Path path) {
    if (!path.startsWith(root)) {
      throw new IllegalArgumentException(
          "Canonical contract source escapes " + root + " through a symbolic link: " + path);
    }
  }

  private static ContractDocument parse(Path source) {
    try {
      String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
      ObjectMapper mapper = fileName.endsWith(".json") ? JSON : YAML;
      JsonNode root = mapper.readTree(Files.readString(source));
      return new ContractDocument(source, root);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Cannot parse canonical contract " + source, exception);
    }
  }
}
