package com.johnowl.gateway.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.Objects;

/**
 * One parsed OAS file.
 *
 * @param path the location of the file, as the caller gave it
 * @param openApi the model from swagger-parser, with references resolved
 * @param raw the document as it is in the file, without resolved references
 */
public record SpecFile(String path, OpenAPI openApi, JsonNode raw) {

    public SpecFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(openApi, "openApi");
        Objects.requireNonNull(raw, "raw");
    }
}
