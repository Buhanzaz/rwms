package dev.buhanzaz.rwms.platform.eventing;

import java.util.Objects;

/**
 * Pure aggregate-version fencing policy for at-least-once consumers.
 * Persistence and record acknowledgement remain service-owned concerns.
 */
public final class RwmsAggregateCheckpointPolicy {

    public Evaluation evaluate(Checkpoint checkpoint, long incomingVersion) {
        Objects.requireNonNull(checkpoint, "checkpoint is required");
        requireEventVersion(incomingVersion);
        if (checkpoint.blocked()) {
            return new Evaluation(Action.BLOCKED, checkpoint, incomingVersion, checkpoint.gapExpectedVersion());
        }

        long expectedVersion = checkpoint.expectedNextVersion();
        if (incomingVersion < expectedVersion) {
            return new Evaluation(Action.DUPLICATE, checkpoint, incomingVersion, expectedVersion);
        }
        if (incomingVersion == expectedVersion) {
            return new Evaluation(Action.APPLY_NEXT, Checkpoint.active(incomingVersion), incomingVersion, expectedVersion);
        }
        return new Evaluation(
                Action.QUARANTINE_GAP,
                Checkpoint.blocked(checkpoint.appliedVersion(), expectedVersion, incomingVersion),
                incomingVersion,
                expectedVersion);
    }

    /**
     * Clears a gap only after the owning service has replayed every missing version.
     * The quarantined event is deliberately not considered applied and must be redelivered.
     */
    public Checkpoint reconcile(Checkpoint checkpoint, long appliedThroughVersion) {
        Objects.requireNonNull(checkpoint, "checkpoint is required");
        requireEventVersion(appliedThroughVersion);
        if (!checkpoint.blocked()) {
            throw new IllegalStateException("only a blocked aggregate can be reconciled");
        }
        long requiredAppliedVersion = checkpoint.quarantinedVersion() - 1;
        if (appliedThroughVersion != requiredAppliedVersion) {
            throw new IllegalArgumentException(
                    "reconciliation must apply every missing version before the quarantined event");
        }
        return Checkpoint.active(appliedThroughVersion);
    }

    private static void requireEventVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("aggregate event version must not be negative");
        }
    }

    public enum Action {
        DUPLICATE,
        APPLY_NEXT,
        QUARANTINE_GAP,
        BLOCKED
    }

    public record Evaluation(Action action, Checkpoint checkpoint, long incomingVersion, long expectedVersion) {

        public Evaluation {
            Objects.requireNonNull(action, "checkpoint action is required");
            Objects.requireNonNull(checkpoint, "result checkpoint is required");
            requireEventVersion(incomingVersion);
            requireEventVersion(expectedVersion);
        }
    }

    public record Checkpoint(
            long appliedVersion, boolean blocked, long gapExpectedVersion, long quarantinedVersion) {

        private static final long NO_VERSION = -1;

        public Checkpoint {
            if (appliedVersion < NO_VERSION) {
                throw new IllegalArgumentException("applied version must not be less than the initial sentinel");
            }
            if (blocked) {
                long actualExpectedVersion = nextVersion(appliedVersion);
                if (gapExpectedVersion != actualExpectedVersion) {
                    throw new IllegalArgumentException("gap expected version must immediately follow the checkpoint");
                }
                if (quarantinedVersion <= gapExpectedVersion) {
                    throw new IllegalArgumentException("quarantined version must be strictly after the gap");
                }
            } else if (gapExpectedVersion != NO_VERSION || quarantinedVersion != NO_VERSION) {
                throw new IllegalArgumentException("active checkpoint must not retain quarantine metadata");
            }
        }

        public static Checkpoint initial() {
            return new Checkpoint(NO_VERSION, false, NO_VERSION, NO_VERSION);
        }

        public static Checkpoint active(long appliedVersion) {
            requireEventVersion(appliedVersion);
            return new Checkpoint(appliedVersion, false, NO_VERSION, NO_VERSION);
        }

        public static Checkpoint blocked(long appliedVersion, long expectedVersion, long quarantinedVersion) {
            return new Checkpoint(appliedVersion, true, expectedVersion, quarantinedVersion);
        }

        public long expectedNextVersion() {
            if (blocked) {
                return gapExpectedVersion;
            }
            return nextVersion(appliedVersion);
        }

        private static long nextVersion(long version) {
            try {
                return Math.addExact(version, 1);
            } catch (ArithmeticException exception) {
                throw new IllegalStateException("aggregate version cannot advance beyond Long.MAX_VALUE", exception);
            }
        }
    }
}
