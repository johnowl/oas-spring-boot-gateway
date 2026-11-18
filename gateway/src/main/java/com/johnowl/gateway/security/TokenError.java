package com.johnowl.gateway.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The log fields of a failed token redemption (spec 023, "Token endpoint errors"). They never contain
 * a token, a client secret, or the {@code error_description} of Entra.
 *
 * @param registrationId the per-audience registration
 * @param status the HTTP status of the token endpoint, or {@code 0} without a response
 * @param error the {@code error} value if it matches {@code ^[a-z_]{1,64}$}, {@value #INVALID} for another
 *     value, or {@code null} without a value
 * @param errorCodes at most 5 numbers of {@code error_codes}, for example {@code 65001} of {@code AADSTS65001}
 * @param failedCheck the failed check of an {@code INVALID_AUDIENCE_TOKEN}, or {@code null}
 */
public record TokenError(String registrationId, int status, String error, List<Long> errorCodes, String failedCheck) {

    /** The logged value of an {@code error} that does not match the pattern. */
    public static final String INVALID = "(invalid)";

    static final int MAX_ERROR_CODES = 5;

    private static final Pattern ERROR = Pattern.compile("^[a-z_]{1,64}$");

    public TokenError {
        errorCodes = List.copyOf(errorCodes.subList(0, Math.min(errorCodes.size(), MAX_ERROR_CODES)));
    }

    /** The fields of an error response. */
    static TokenError of(String registrationId, int status, Object error, List<Long> errorCodes) {
        return new TokenError(registrationId, status, safeError(error), errorCodes, null);
    }

    /** The fields of a redemption without an error response, for example a timeout. */
    static TokenError withoutResponse(String registrationId) {
        return new TokenError(registrationId, 0, null, List.of(), null);
    }

    /** The fields of a token that failed the validation. */
    static TokenError invalidToken(String registrationId, String failedCheck) {
        return new TokenError(registrationId, 200, null, List.of(), failedCheck);
    }

    private static String safeError(Object error) {
        if (error == null) {
            return null;
        }
        return error instanceof String value && ERROR.matcher(value).matches() ? value : INVALID;
    }
}
