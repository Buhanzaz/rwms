package dev.buhanzaz.rwms.auth.eventing;

import java.util.Set;

public final class AuthEventTypes {

    public static final String USER_CREATED = "auth.user-authorization.created.v1";
    public static final String USER_CHANGED = "auth.user-authorization.changed.v1";
    public static final String USER_PROFILE_CHANGED = "auth.user-authorization.profile-changed.v1";
    public static final String USER_PASSWORD_CHANGED = "auth.user-authorization.password-changed.v1";
    public static final String USER_GRANTS_CHANGED = "auth.user-authorization.warehouse-grants-changed.v1";
    public static final String USER_BOOTSTRAPPED = "auth.user-authorization.bootstrapped.v1";
    public static final String WORKER_CONFIGURED = "auth.worker-access.configured.v1";
    public static final String WORKER_PASSWORD_RESET = "auth.worker-access.password-reset.v1";
    public static final String WORKER_DISABLED = "auth.worker-access.disabled.v1";
    public static final String WORKER_DELETED = "auth.worker-access.deleted.v1";

    public static final Set<String> USER_FACTS = Set.of(
            USER_CREATED,
            USER_CHANGED,
            USER_PROFILE_CHANGED,
            USER_PASSWORD_CHANGED,
            USER_GRANTS_CHANGED,
            USER_BOOTSTRAPPED);
    public static final Set<String> WORKER_FACTS = Set.of(
            WORKER_CONFIGURED,
            WORKER_PASSWORD_RESET,
            WORKER_DISABLED,
            WORKER_DELETED);
    public static final Set<String> ALL = java.util.stream.Stream.concat(
                    USER_FACTS.stream(), WORKER_FACTS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AuthEventTypes() {}
}
