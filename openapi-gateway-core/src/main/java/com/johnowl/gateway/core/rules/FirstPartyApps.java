package com.johnowl.gateway.core.rules;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Well-known Microsoft first-party apps that an OAS file must not declare as its audience (spec 018, "Rules for
 * audiences"). Each app id comes from the Microsoft documentation:
 * https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/governance/verify-first-party-apps-sign-in
 */
final class FirstPartyApps {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("00000003-0000-0000-c000-000000000000", "Microsoft Graph"),
            Map.entry("797f4846-ba00-4fd7-ba43-dac1f8f63013", "Azure Resource Manager"),
            Map.entry("cfa8b339-82a2-471a-a3c9-0fc0be7a4093", "Azure Key Vault"),
            Map.entry("e406a681-f3d4-42a8-90b6-c2b029497af1", "Azure Storage"),
            Map.entry("00000002-0000-0ff1-ce00-000000000000", "Exchange Online"),
            Map.entry("00000003-0000-0ff1-ce00-000000000000", "SharePoint Online"),
            Map.entry("00000002-0000-0000-c000-000000000000", "Azure AD Graph"));

    private FirstPartyApps() {
    }

    /** All app ids of the list, with the name of each app. */
    static Map<String, String> all() {
        return NAMES;
    }

    /** The name of the first-party app with this app id, or empty. The comparison ignores the case. */
    static Optional<String> name(String appId) {
        return Optional.ofNullable(NAMES.get(appId.toLowerCase(Locale.ROOT)));
    }
}
