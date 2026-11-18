package com.johnowl.gateway.core.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.spec.SpecFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * The root-level {@code x-gateway} object of one OAS file.
 *
 * @param upstream {@code upstream}
 * @param prefix {@code prefix}; {@code null} if not set
 * @param upstreamBasePath {@code upstream-base-path}; {@code null} if not set
 * @param audience {@code audience}; {@code null} if not set
 */
public record XGatewaySettings(URI upstream, String prefix, String upstreamBasePath, String audience) {

    /**
     * Returns empty if the file has no valid {@code x-gateway.upstream}: missing, not text, or not an absolute
     * URI. Never throws.
     */
    public static Optional<XGatewaySettings> of(SpecFile spec) {
        JsonNode xGateway = spec.raw().get("x-gateway");
        Optional<URI> upstream = xGateway == null ? Optional.empty() : upstream(xGateway.get("upstream"));
        if (upstream.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new XGatewaySettings(
                upstream.get(),
                text(xGateway, "prefix"),
                text(xGateway, "upstream-base-path"),
                text(xGateway, "audience")));
    }

    /**
     * The public path of an OAS path: the prefix and the OAS path. The OAS path {@code /} with a prefix gives the
     * prefix alone, so that the public path has no trailing slash.
     */
    public String publicPath(String oasPath) {
        if (prefix == null) {
            return oasPath;
        }
        return oasPath.equals("/") ? prefix : prefix + oasPath;
    }

    private static Optional<URI> upstream(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(value.asText());
            return uri.isAbsolute() ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
