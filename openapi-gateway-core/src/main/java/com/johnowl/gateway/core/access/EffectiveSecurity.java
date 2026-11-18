package com.johnowl.gateway.core.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.validation.JsonPointers;
import java.util.Locale;

/**
 * The effective {@code security} of one operation, read from the raw document, because the
 * parsed model does not show the difference between an absent field and {@code security: []}.
 *
 * <ul>
 *   <li>The operation field replaces the root field. The two fields do not merge.
 *   <li>{@code security: []} on the operation makes the operation public.
 *   <li>Without an operation field, a non-empty root field applies. A root {@code security: []}
 *       does not make operations public: public is explicit on each operation.
 * </ul>
 *
 * @param kind public, required, or missing
 * @param requirements the security requirement array; {@code null} unless {@code kind} is REQUIRED
 * @param pointer the JSON pointer of the effective {@code security} field; {@code null} if missing
 */
public record EffectiveSecurity(Kind kind, JsonNode requirements, String pointer) {

    public enum Kind {
        PUBLIC,
        REQUIRED,
        MISSING
    }

    public static EffectiveSecurity of(SpecFile spec, String oasPath, String method) {
        String operationPointer = "/paths/" + JsonPointers.escape(oasPath) + "/" + method.toLowerCase(Locale.ROOT);
        JsonNode operation = spec.raw().at(operationPointer);
        JsonNode own = operation.isObject() ? operation.get("security") : null;
        if (own != null) {
            if (own.isArray() && own.isEmpty()) {
                return new EffectiveSecurity(Kind.PUBLIC, null, operationPointer + "/security");
            }
            return new EffectiveSecurity(Kind.REQUIRED, own, operationPointer + "/security");
        }
        JsonNode root = spec.raw().get("security");
        if (root != null && !(root.isArray() && root.isEmpty())) {
            return new EffectiveSecurity(Kind.REQUIRED, root, "/security");
        }
        return new EffectiveSecurity(Kind.MISSING, null, null);
    }

    public boolean isPublic() {
        return kind == Kind.PUBLIC;
    }
}
