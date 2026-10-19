package dev.buhanzaz.rwms.platform.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TechnicalContractsArchitectureTest {

    private static final List<String> FORBIDDEN_BINARY_REFERENCES = List.of(
            "org/springframework/",
            "jakarta/persistence/",
            "javax/persistence/",
            "org/hibernate/",
            "/repository/",
            "/repositories/",
            "/entity/",
            "/entities/",
            "/domain/");

    private static final Set<Class<?>> ALLOWED_COMPONENT_TYPES = Set.of(
            String.class,
            Object.class,
            URI.class,
            Instant.class,
            UUID.class,
            List.class,
            ActorSnapshot.class,
            OpaqueActorReference.class,
            CorrelationContext.class,
            int.class,
            long.class);

    @Test
    void compiledContractsDoNotReferenceFrameworkPersistenceOrDomainPackages() throws Exception {
        var classFiles = compiledContractClassFiles();

        assertFalse(classFiles.isEmpty(), "compiled contract classes must be present");
        assertAll(classFiles.stream().flatMap(classFile -> FORBIDDEN_BINARY_REFERENCES.stream()
                .map(forbiddenReference -> () -> assertBinaryReferenceAbsent(classFile, forbiddenReference))));
    }

    @Test
    void everyExportedContractIsAFinalRecordWithTechnicalComponentsOnly() throws Exception {
        var exportedContracts = compiledContractClasses().stream()
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .toList();

        assertFalse(exportedContracts.isEmpty(), "exported contract classes must be present");
        assertAll(exportedContracts.stream().map(type -> () -> {
            assertTrue(type.isRecord(), () -> type.getName() + " must be a record");
            assertTrue(Modifier.isFinal(type.getModifiers()), () -> type.getName() + " must be final");
            assertAll(Stream.of(type.getRecordComponents()).map(component -> () -> assertTrue(
                    ALLOWED_COMPONENT_TYPES.contains(component.getType()),
                    () -> type.getName() + "." + component.getName()
                            + " exposes non-technical type " + component.getType().getName())));
        }));
    }

    private static void assertBinaryReferenceAbsent(Path classFile, String forbiddenReference) throws IOException {
        var binaryText = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        assertFalse(
                binaryText.contains(forbiddenReference),
                () -> classFile.getFileName() + " references forbidden package " + forbiddenReference);
    }

    private static List<Class<?>> compiledContractClasses() throws Exception {
        var classesRoot = compiledClassesRoot();
        try (var paths = Files.walk(classesRoot)) {
            return paths.filter(path -> path.toString().endsWith(".class"))
                    .map(classesRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.substring(0, path.length() - ".class".length()))
                    .map(path -> path.replace('/', '.').replace('\\', '.'))
                    .map(TechnicalContractsArchitectureTest::loadClass)
                    .toList();
        }
    }

    private static List<Path> compiledContractClassFiles() throws Exception {
        try (var paths = Files.walk(compiledClassesRoot())) {
            return paths.filter(path -> path.toString().endsWith(".class")).toList();
        }
    }

    private static Path compiledClassesRoot() throws Exception {
        return Path.of(ApiProblem.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, TechnicalContractsArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load compiled contract " + className, exception);
        }
    }
}
