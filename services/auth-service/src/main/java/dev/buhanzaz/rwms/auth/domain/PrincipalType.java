package dev.buhanzaz.rwms.auth.domain;

/** Distinguishes interactive management users from worker-application identities. */
public enum PrincipalType {
    /** A named RWMS user who is assigned a global role and optional warehouse grants. */
    USER,
    /** A credential bound to one external worker and warehouse for the worker application. */
    WORKER
}
