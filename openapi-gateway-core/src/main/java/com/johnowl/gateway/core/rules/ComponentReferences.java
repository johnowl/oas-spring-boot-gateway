package com.johnowl.gateway.core.rules;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves a {@code $ref} to a component. swagger-parser resolves external references into {@code components}, but
 * it keeps a local {@code $ref}, for example to {@code #/components/responses/<name>}, in the model.
 */
final class ComponentReferences {

    private ComponentReferences() {
    }

    /** The response that {@code response} refers to, also through several references, or {@code null}. */
    static ApiResponse response(OpenAPI openApi, ApiResponse response) {
        Components components = openApi.getComponents();
        return resolve(components == null ? null : components.getResponses(), "#/components/responses/",
                response, ApiResponse::get$ref);
    }

    /** The request body that {@code body} refers to, also through several references, or {@code null}. */
    static RequestBody requestBody(OpenAPI openApi, RequestBody body) {
        Components components = openApi.getComponents();
        return resolve(components == null ? null : components.getRequestBodies(), "#/components/requestBodies/",
                body, RequestBody::get$ref);
    }

    /** The schema that {@code schema} refers to, or {@code null}. Only one step: OAS 3.1 permits siblings. */
    @SuppressWarnings("rawtypes")
    static Schema schema(OpenAPI openApi, Schema schema) {
        Components components = openApi.getComponents();
        Map<String, Schema> named = components == null ? null : components.getSchemas();
        String ref = schema.get$ref();
        String prefix = "#/components/schemas/";
        if (ref == null || !ref.startsWith(prefix) || named == null) {
            return null;
        }
        return named.get(name(ref, prefix));
    }

    private static <T> T resolve(Map<String, T> named, String prefix, T start, Function<T, String> refOf) {
        Set<String> seen = new HashSet<>();
        T current = start;
        while (current != null && refOf.apply(current) != null) {
            String ref = refOf.apply(current);
            if (!ref.startsWith(prefix) || named == null || !seen.add(ref)) {
                return null;
            }
            current = named.get(name(ref, prefix));
        }
        return current;
    }

    /** The component name of a reference, with the RFC 6901 escapes {@code ~1} and {@code ~0} decoded. */
    private static String name(String ref, String prefix) {
        return ref.substring(prefix.length()).replace("~1", "/").replace("~0", "~");
    }
}
