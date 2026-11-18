package com.johnowl.gateway.core.validation;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.util.Locale;
import java.util.Objects;

/**
 * One operation that a rule checks.
 *
 * @param spec the file of the operation
 * @param path the path template, for example {@code /users/{id}}
 * @param method the HTTP method
 * @param operation the operation, with references resolved
 * @param pathItem the path item that contains the operation
 */
public record OperationContext(
        SpecContext spec, String path, PathItem.HttpMethod method, Operation operation, PathItem pathItem) {

    public OperationContext {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(pathItem, "pathItem");
    }

    /** The JSON pointer of the operation, for example {@code /paths/~1users~1{id}/get}. */
    public String pointer() {
        return "/paths/" + JsonPointers.escape(path) + "/" + method.name().toLowerCase(Locale.ROOT);
    }
}
