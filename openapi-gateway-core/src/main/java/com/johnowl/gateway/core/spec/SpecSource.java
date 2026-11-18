package com.johnowl.gateway.core.spec;

import java.util.Objects;

/**
 * The content of one OAS file and the location that violations show.
 *
 * @param path the location of the file, for example {@code openapi/orders.yaml}
 * @param content the file content
 */
public record SpecSource(String path, String content) {

    public SpecSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
    }
}
