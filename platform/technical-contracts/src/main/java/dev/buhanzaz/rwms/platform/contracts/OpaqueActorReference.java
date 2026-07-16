package dev.buhanzaz.rwms.platform.contracts;

import java.util.regex.Pattern;

/** Sanitized reference to actor data retained only by its owning service. */
public record OpaqueActorReference(String subjectId, String principalType, String profileRevision) {

    private static final Pattern OPAQUE_SUBJECT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OPAQUE_PROFILE_REVISION = Pattern.compile(
            "(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{64})");
    private static final Pattern PRINCIPAL_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public OpaqueActorReference {
        requireMatch(subjectId, OPAQUE_SUBJECT_ID, "subjectId");
        requireMatch(principalType, PRINCIPAL_TYPE, "principalType");
        if (profileRevision != null) {
            requireMatch(profileRevision, OPAQUE_PROFILE_REVISION, "profileRevision");
        }
    }

    private static void requireMatch(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an opaque technical value");
        }
    }
}
