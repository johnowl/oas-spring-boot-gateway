package com.johnowl.gateway.core.spec;

import java.util.Set;

/** The fields of an OAS 3.0 and 3.1 Path Item Object that hold an operation. */
public final class PathItemMethods {

    /** The HTTP methods in lowercase, as the field names of a path item. */
    public static final Set<String> NAMES =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private PathItemMethods() {
    }
}
