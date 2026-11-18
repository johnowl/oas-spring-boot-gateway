package com.johnowl.gateway.core.access;

import java.util.Objects;

/**
 * A new or changed audience binding: the {@code x-gateway.audience} of one head file, and the {@code upstream}, the
 * {@code prefix}, and the {@code upstream-base-path} of that file. The gateway sends the token of the audience to that
 * upstream. A file without an audience also has a binding: the gateway sends its public requests to that upstream. See
 * specs/034-access-change-review.md, "Audience bindings".
 *
 * @param kind new or changed
 * @param audience {@code x-gateway.audience}; {@code null} for a file without an audience
 * @param baseUpstream the upstream of the audience in the base; {@code null} for {@link Kind#NEW}
 * @param headUpstream the upstream of the audience in the head
 * @param basePrefix the prefix of the audience in the base; {@code null} without a prefix, and for {@link Kind#NEW}
 * @param headPrefix the prefix of the audience in the head; {@code null} without a prefix
 * @param baseUpstreamBasePath the upstream base path of the audience in the base; {@code null} without an upstream
 *     base path, and for {@link Kind#NEW}
 * @param headUpstreamBasePath the upstream base path of the audience in the head; {@code null} without an upstream
 *     base path
 * @param file the head file
 * @param pointer the JSON pointer of the changed field in the head file; {@code /x-gateway} if the head file has no
 *     such field
 */
public record AudienceBinding(Kind kind, String audience, String baseUpstream, String headUpstream, String basePrefix,
        String headPrefix, String baseUpstreamBasePath, String headUpstreamBasePath, String file, String pointer) {

    public enum Kind {
        NEW("binding-new"),
        CHANGED("binding-changed");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** The kind of the output, for example {@code binding-new}. */
        public String id() {
            return id;
        }
    }

    public AudienceBinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(headUpstream, "headUpstream");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(pointer, "pointer");
        if ((kind == Kind.NEW) != (baseUpstream == null)) {
            throw new IllegalArgumentException("Only a changed binding has a base upstream");
        }
    }
}
