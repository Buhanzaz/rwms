package dev.buhanzaz.rwms.auth.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthProjectionOwnershipArchitectureTest {

    private static final String PROJECTION_WRITER = AuthProjectionWriter.class.getName();
    private static final Set<String> ENTITY_MUTATIONS = Set.of(
            "registerUser",
            "registerWorker",
            "changeUserProfile",
            "changeUserAuthorization",
            "changePasswordHash",
            "reconfigureWorker",
            "disable",
            "touch",
            "define");
    private static final Set<String> REPOSITORY_MUTATIONS = Set.of(
            "save",
            "saveAll",
            "saveAndFlush",
            "saveAllAndFlush",
            "flush",
            "delete",
            "deleteById",
            "deleteAll",
            "deleteAllById",
            "deleteAllByIdInBatch",
            "deleteAllInBatch");

    @Test
    void onlyProjectionWriterMayCallJpaAndProjectionMutators() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.buhanzaz.rwms.auth");

        var calls = classes.stream()
                .flatMap(type -> type.getMethodCallsFromSelf().stream())
                .toList();
        var violations = calls.stream()
                .filter(call -> !call.getOriginOwner().getName().equals(PROJECTION_WRITER))
                .filter(this::isProjectionMutation)
                .map(JavaMethodCall::getDescription)
                .sorted()
                .toList();

        assertThat(violations)
                .as("projection writes and invariant mutations must remain owned by AuthProjectionWriter")
                .isEmpty();
        assertThat(calls.stream()
                        .filter(call -> call.getOriginOwner().getName().equals(PROJECTION_WRITER))
                        .filter(this::isProjectionMutation)
                        .map(JavaMethodCall::getName))
                .as("the bytecode classifier must detect the known projection-writer canaries")
                .contains("saveAndFlush", "saveAllAndFlush", "delete");
    }

    private boolean isProjectionMutation(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String method = call.getName();
        boolean entityMutation = (owner.equals("dev.buhanzaz.rwms.auth.domain.AuthSubject")
                        || owner.equals("dev.buhanzaz.rwms.auth.domain.UserWarehouseAccess"))
                && ENTITY_MUTATIONS.contains(method);
        boolean repositoryMutation = (owner.startsWith("org.springframework.data.")
                        || owner.equals("dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository")
                        || owner.equals("dev.buhanzaz.rwms.auth.repository.UserWarehouseAccessRepository"))
                && REPOSITORY_MUTATIONS.contains(method);
        return entityMutation || repositoryMutation;
    }
}
