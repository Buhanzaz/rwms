package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AuthEventPayloads {

    public record WarehouseGrantFact(
            UUID accessId,
            UUID warehouseId,
            WarehouseAccessLevel level,
            boolean active,
            UUID noteRevision) {

        public WarehouseGrantFact {
            Objects.requireNonNull(accessId, "accessId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(noteRevision, "noteRevision");
        }
    }

    public record UserAuthorizationFact(
            UUID subjectId,
            boolean active,
            boolean mobileAppAccess,
            UserGlobalRole globalRole,
            UUID profileRevision,
            List<WarehouseGrantFact> warehouseAccess) {

        public UserAuthorizationFact(
                UUID subjectId,
                boolean active,
                UserGlobalRole globalRole,
                UUID profileRevision,
                List<WarehouseGrantFact> warehouseAccess) {
            this(
                    subjectId,
                    active,
                    false,
                    globalRole,
                    profileRevision,
                    warehouseAccess);
        }

        public UserAuthorizationFact {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(globalRole, "globalRole");
            Objects.requireNonNull(profileRevision, "profileRevision");
            if (mobileAppAccess && !globalRole.isManagerAppEligible()) {
                throw new IllegalArgumentException(
                        "Mobile app access requires an eligible user role");
            }
            warehouseAccess = List.copyOf(warehouseAccess);
            if (warehouseAccess.stream().map(WarehouseGrantFact::accessId).distinct().count()
                    != warehouseAccess.size()) {
                throw new IllegalArgumentException("Warehouse access ids must be unique");
            }
            if (warehouseAccess.stream().map(WarehouseGrantFact::warehouseId).distinct().count()
                    != warehouseAccess.size()) {
                throw new IllegalArgumentException("Warehouse ids must be unique");
            }
        }
    }

    public enum WorkerCredentialFactStatus {
        ACTIVE,
        DISABLED
    }

    public record WorkerAccessFact(
            UUID subjectId,
            UUID workerLink,
            UUID warehouseId,
            boolean active,
            WorkerCredentialFactStatus credentialStatus) {

        public WorkerAccessFact {
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(workerLink, "workerLink");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(credentialStatus, "credentialStatus");
            if (!subjectId.equals(workerLink)) {
                throw new IllegalArgumentException("workerLink must be the opaque auth subject id");
            }
            if (active != (credentialStatus == WorkerCredentialFactStatus.ACTIVE)) {
                throw new IllegalArgumentException("Worker active state must match credentialStatus");
            }
        }
    }

    private AuthEventPayloads() {}
}
