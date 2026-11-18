package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class SessionSafeClaimsTest {

    private static final Map<String, Object> CLAIMS = Map.of(
            "iss", "https://login.example.com/tenant/v2.0",
            "aud", "client-id-1",
            "sub", "subject-1",
            "oid", "oid-1",
            "exp", 1_900_000_000L,
            "iat", 1_800_000_000L,
            "roles", List.of("Admin"),
            "xms_object", new TreeMap<>(Map.of("a", "b")));

    private final SessionJsonSerializer serializer = new SessionJsonSerializer(getClass().getClassLoader());

    @Test
    void sessionWithDefaultClaimTypesDoesNotRead() {
        Converter<Map<String, Object>, Map<String, Object>> defaults =
                new ClaimTypeConverter(ReactiveOidcIdTokenDecoderFactory.createDefaultClaimTypeConverters());

        Object read = serializer.deserialize(serializer.serialize(context(defaults.convert(CLAIMS))));

        assertThat(read).as("iss is a java.net.URL").isSameAs(SessionJsonSerializer.UNREADABLE);
    }

    @Test
    void sessionWithSafeClaimsReadsBack() throws Exception {
        Map<String, Object> claims = new SessionSafeClaims().convert(CLAIMS);

        Object read = serializer.deserialize(serializer.serialize(context(claims)));

        assertThat(read).isInstanceOf(SecurityContextImpl.class);
        DefaultOidcUser user = (DefaultOidcUser) ((SecurityContextImpl) read).getAuthentication().getPrincipal();
        assertThat(user.getName()).isEqualTo("oid-1");
        assertThat(user.getIdToken().getIssuer()).isEqualTo(URI.create("https://login.example.com/tenant/v2.0").toURL());
        assertThat(user.getIdToken().getExpiresAt()).isEqualTo(Instant.ofEpochSecond(1_900_000_000L));
        assertThat(user.getIdToken().getAudience()).containsExactly("client-id-1");
        assertThat(user.getIdToken().getClaimAsStringList("roles")).containsExactly("Admin");
        assertThat(user.getIdToken().getClaimAsMap("xms_object")).containsEntry("a", "b");
    }

    private static SecurityContextImpl context(Map<String, Object> claims) {
        OidcIdToken idToken = new OidcIdToken("id-token-value", Instant.ofEpochSecond(1_800_000_000L),
                Instant.ofEpochSecond(1_900_000_000L), claims);
        DefaultOidcUser user = new DefaultOidcUser(Set.of(new OidcUserAuthority(idToken)), idToken, "oid");
        return new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "entra"));
    }
}
