package com.johnowl.gateway.routing;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Locations of the OAS files.
 *
 * @param specs Spring resource patterns, for example {@code classpath:openapi/*.yaml} or
 *     {@code file:/etc/gateway/openapi/*.yaml}. The default reads the files {@code *.yaml},
 *     {@code *.yml} and {@code *.json} in {@code classpath:openapi/}, the same extensions that the
 *     CLI validates.
 */
@ConfigurationProperties("gateway.openapi")
public record GatewayOpenApiProperties(
        @DefaultValue({"classpath:openapi/*.yaml", "classpath:openapi/*.yml", "classpath:openapi/*.json"})
        List<String> specs) {

    public GatewayOpenApiProperties {
        specs = List.copyOf(specs);
    }
}
