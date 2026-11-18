package com.johnowl.gateway.security;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;

/**
 * Converts the claims of the ID token to types that the session serializer reads back
 * ({@link SessionJsonSerializer}). The default converter of Spring Security makes {@code iss} a
 * {@link URL}, and the JSON parser can give its own map type for an object claim. These types are
 * not in the type allowlist, so the session would not load after login.
 *
 * <p>The converter keeps the default conversions (for example {@code exp} as an {@code Instant}),
 * then writes a {@code URL} as a string, each object as a {@link LinkedHashMap}, and each array as an
 * {@link ArrayList}. {@code OidcIdToken.getIssuer()} still returns a {@code URL}.
 */
final class SessionSafeClaims implements Converter<Map<String, Object>, Map<String, Object>> {

    private final Converter<Map<String, Object>, Map<String, Object>> defaults =
            new ClaimTypeConverter(ReactiveOidcIdTokenDecoderFactory.createDefaultClaimTypeConverters());

    @Override
    public Map<String, Object> convert(Map<String, Object> claims) {
        Map<String, Object> converted = defaults.convert(claims);
        return converted == null ? null : safeMap(converted);
    }

    private static Map<String, Object> safeMap(Map<?, ?> map) {
        Map<String, Object> safe = new LinkedHashMap<>();
        map.forEach((name, value) -> safe.put(String.valueOf(name), safe(value)));
        return safe;
    }

    private static Object safe(Object value) {
        return switch (value) {
            case URL url -> url.toExternalForm();
            case Map<?, ?> map -> safeMap(map);
            case Collection<?> collection -> {
                List<Object> list = new ArrayList<>(collection.size());
                collection.forEach(element -> list.add(safe(element)));
                yield list;
            }
            case null, default -> value;
        };
    }
}
