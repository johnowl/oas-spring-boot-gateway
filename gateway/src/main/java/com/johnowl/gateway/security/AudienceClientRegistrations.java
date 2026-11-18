package com.johnowl.gateway.security;

import com.johnowl.gateway.core.access.OperationAccess;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The per-audience client registrations (spec 023, "Client registrations for each audience"). There
 * is one registration {@code entra-<client-id>} for each audience of a protected operation of the
 * loaded OAS files. Each registration copies the client and the provider of the registration
 * {@code entra} ({@code gateway-bff}). Its grant type is {@code refresh_token} and its scope is
 * {@code <client-id>/.default}.
 *
 * <p>The startup stops if an audience is the client id of {@code gateway-bff}: Entra issues a token for
 * {@code gateway-bff} itself, and the gateway would relay it to the backend.
 */
public final class AudienceClientRegistrations {

    private final Map<String, ClientRegistration> registrations;

    private AudienceClientRegistrations(Map<String, ClientRegistration> registrations) {
        this.registrations = registrations;
    }

    /**
     * Builds the registrations of the audiences of the protected operations.
     *
     * @param entra the registration {@code entra}
     * @param accessRules the access rules of the loaded OAS files
     * @throws IllegalStateException if an audience is the client id of {@code entra}
     */
    public static AudienceClientRegistrations of(ClientRegistration entra, Collection<OperationAccess> accessRules) {
        List<String> audiences = accessRules.stream()
                .map(access -> access.rule().audience())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
        for (String audience : audiences) {
            // The property value of the client id can be uppercase; a GUID is the same in both cases.
            if (audience.equalsIgnoreCase(entra.getClientId())) {
                throw new IllegalStateException("x-gateway.audience '" + audience + "' is the client id of gateway-bff "
                        + "(registration '" + entra.getRegistrationId() + "'). The gateway does not request tokens for itself.");
            }
            ClientRegistration registration = ClientRegistration.withClientRegistration(entra)
                    .registrationId(AudienceRegistrationIds.forAudience(audience))
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri(null)
                    .scope(scope(audience))
                    .build();
            registrations.put(registration.getRegistrationId(), registration);
        }
        return new AudienceClientRegistrations(Map.copyOf(registrations));
    }

    /** The scope of the registration of an audience: {@code <client-id>/.default}. */
    static String scope(String audience) {
        return audience + "/.default";
    }

    /** All per-audience registrations. */
    public Collection<ClientRegistration> registrations() {
        return registrations.values();
    }

    /** The ids of all per-audience registrations. */
    public Set<String> registrationIds() {
        return registrations.keySet();
    }

    /** {@code true} only for the id of a per-audience registration of this set. */
    public boolean contains(String registrationId) {
        return registrationId != null && registrations.containsKey(registrationId);
    }
}
