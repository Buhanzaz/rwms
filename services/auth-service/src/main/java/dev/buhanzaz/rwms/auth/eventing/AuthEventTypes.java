package dev.buhanzaz.rwms.auth.eventing;

import java.util.Set;

/**
 * Versioned event-type identifiers emitted by the auth service.
 *
 * <p>The sets group event types by aggregate family for payload validation and routing. The
 * identifiers describe safe authorization and worker-access facts, not private profile or
 * credential data.
 */
public final class AuthEventTypes {

    /** Initial user-authorization snapshot. */
    public static final String USER_CREATED = "auth.user-authorization.created.v1";
    /** User authorization snapshot after a general authorization change. */
    public static final String USER_CHANGED = "auth.user-authorization.changed.v1";
    /** User authorization snapshot after private profile data changes. */
    public static final String USER_PROFILE_CHANGED = "auth.user-authorization.profile-changed.v1";
    /** User authorization snapshot after a credential change. */
    public static final String USER_PASSWORD_CHANGED = "auth.user-authorization.password-changed.v1";
    /** User authorization snapshot after warehouse grants change. */
    public static final String USER_GRANTS_CHANGED = "auth.user-authorization.warehouse-grants-changed.v1";
    /** User authorization snapshot created by controlled bootstrap. */
    public static final String USER_BOOTSTRAPPED = "auth.user-authorization.bootstrapped.v1";
    /** Worker-access snapshot after a worker is configured. */
    public static final String WORKER_CONFIGURED = "auth.worker-access.configured.v1";
    /** Worker-access snapshot after a worker credential is reset. */
    public static final String WORKER_PASSWORD_RESET = "auth.worker-access.password-reset.v1";
    /** Worker-access snapshot after a worker is disabled. */
    public static final String WORKER_DISABLED = "auth.worker-access.disabled.v1";
    /** Terminal worker-access snapshot emitted before projection deletion. */
    public static final String WORKER_DELETED = "auth.worker-access.deleted.v1";

    /** Event types whose payload must be a user-authorization fact. */
    public static final Set<String> USER_FACTS = Set.of(
            USER_CREATED,
            USER_CHANGED,
            USER_PROFILE_CHANGED,
            USER_PASSWORD_CHANGED,
            USER_GRANTS_CHANGED,
            USER_BOOTSTRAPPED);
    /** Event types whose payload must be a worker-access fact. */
    public static final Set<String> WORKER_FACTS = Set.of(
            WORKER_CONFIGURED,
            WORKER_PASSWORD_RESET,
            WORKER_DISABLED,
            WORKER_DELETED);
    /** Complete allow-list of auth event types accepted by the payload policy. */
    public static final Set<String> ALL = java.util.stream.Stream.concat(
                    USER_FACTS.stream(), WORKER_FACTS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AuthEventTypes() {}
}
