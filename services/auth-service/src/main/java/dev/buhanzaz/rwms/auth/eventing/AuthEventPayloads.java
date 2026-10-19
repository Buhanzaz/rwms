package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe, immutable payload shapes for auth's published facts.
 *
 * <p>These records intentionally contain authorization identifiers, state, and revisions rather
 * than credentials or profile data. Their constructors enforce invariants before a payload can be
 * persisted in the authoritative event stream or sent through the outbox.
 */
public final class AuthEventPayloads {

    /**
     * A warehouse-access grant included in a user-authorization fact.
     *
     * @param accessId stable access-grant identifier
     * @param warehouseId warehouse identifier
     * @param level granted access level
     * @param active whether the grant is currently usable
     * @param noteRevision opaque revision of the separately stored access note
     */
    public record WarehouseGrantFact(
            UUID accessId,
            UUID warehouseId,
            WarehouseAccessLevel level,
            boolean active,
            UUID noteRevision) {

        /** Validates that every identifier and access level required by the fact is present. */
        public WarehouseGrantFact {
            Objects.requireNonNull(accessId, "accessId");
            Objects.requireNonNull(warehouseId, "warehouseId");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(noteRevision, "noteRevision");
        }
    }

    /**
     * A complete safe authorization snapshot for a user aggregate.
     *
     * <p>The snapshot gives consumers enough information to invalidate or project authorization
     * state without duplicating profile data, credentials, or note content.
     *
     * @param subjectId opaque auth-subject identifier
     * @param active whether the subject is active
     * @param mobileAppAccess whether the subject may use the manager mobile application
     * @param rentalAccess whether the subject may use rental-management capabilities
     * @param globalRole authorization role
     * @param profileRevision opaque revision of the private profile vault entry
     * @param warehouseAccess immutable warehouse-grant snapshot
     */
    public record UserAuthorizationFact(
            UUID subjectId,
            boolean active,
            boolean mobileAppAccess,
            boolean rentalAccess,
            UserGlobalRole globalRole,
            UUID profileRevision,
            List<WarehouseGrantFact> warehouseAccess) {

        /**
         * Creates a legacy-compatible user fact with mobile application access disabled.
         *
         * @param subjectId opaque auth-subject identifier
         * @param active whether the subject is active
         * @param globalRole authorization role
         * @param profileRevision private-profile revision used for invalidation
         * @param warehouseAccess warehouse grants to include
         */
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
                    false,
                    globalRole,
                    profileRevision,
                    warehouseAccess);
        }

        /**
         * Creates the pre-rental-access fact shape used by existing source callers.
         *
         * @param subjectId opaque auth-subject identifier
         * @param active whether the subject is active
         * @param mobileAppAccess manager-mobile entitlement
         * @param globalRole authorization role
         * @param profileRevision private-profile revision used for invalidation
         * @param warehouseAccess warehouse grants to include
         */
        public UserAuthorizationFact(
                UUID subjectId,
                boolean active,
                boolean mobileAppAccess,
                UserGlobalRole globalRole,
                UUID profileRevision,
                List<WarehouseGrantFact> warehouseAccess) {
            this(
                    subjectId,
                    active,
                    mobileAppAccess,
                    false,
                    globalRole,
                    profileRevision,
                    warehouseAccess);
        }

        /**
         * Validates authorization invariants and copies the warehouse-grant collection.
         *
         * <p>Mobile access requires an eligible role, and both grant and warehouse identifiers must
         * be unique so the snapshot cannot encode contradictory access state.
         */
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

    /** Credential state that can be safely represented in a worker-access fact. */
    public enum WorkerCredentialFactStatus {
        /** Credential is enabled and consistent with active worker access. */
        ACTIVE,
        /** Credential is disabled and consistent with inactive worker access. */
        DISABLED
    }

    /**
     * A safe worker-access snapshot for a worker aggregate.
     *
     * @param subjectId opaque auth-subject identifier
     * @param workerLink opaque link used by worker consumers and constrained to the subject ID
     * @param warehouseId warehouse to which the worker belongs
     * @param active whether worker access is active
     * @param credentialStatus safe credential availability state
     */
    public record WorkerAccessFact(
            UUID subjectId,
            UUID workerLink,
            UUID warehouseId,
            boolean active,
            WorkerCredentialFactStatus credentialStatus) {
        /**
         * Validates the opaque subject link and keeps active state consistent with credentials.
         */
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
