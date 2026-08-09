package dev.buhanzaz.rwms.contracts;

import java.nio.file.Path;

/**
 * Coordinates deterministic structural validation of the repository's canonical HTTP, event and
 * JSON Schema sources without deriving unrecorded cross-service topology semantics.
 */
final class CanonicalContractIntegrityGate {

  /**
   * Parses every machine-readable source and applies reference, OpenAPI, JSON Schema and AsyncAPI
   * catalog checks in dependency order.
   *
   * @param contractsRoot the repository's canonical {@code contracts/} directory
   */
  void verify(Path contractsRoot) {
    ContractDocumentRepository repository = ContractDocumentRepository.load(contractsRoot);
    verifyRecognizedKinds(repository);
    ContractReferenceVerifier references = new ContractReferenceVerifier(repository);
    references.verifyAll();
    new OpenApiContractVerifier().verify(repository);
    new CanonicalSchemaCompiler().verify(repository);
    new EventCatalogVerifier(references).verify(repository);
  }

  private static void verifyRecognizedKinds(ContractDocumentRepository repository) {
    for (ContractDocument document : repository.documents()) {
      if (!document.isOpenApi() && !document.isEventCatalog() && !document.isJsonSchema()) {
        throw new IllegalArgumentException(
            "Canonical contract "
                + document.path()
                + " must declare openapi, asyncapi or $schema at its root");
      }
    }
  }
}
