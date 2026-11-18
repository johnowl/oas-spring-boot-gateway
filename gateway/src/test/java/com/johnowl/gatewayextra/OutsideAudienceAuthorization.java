package com.johnowl.gatewayextra;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;

/**
 * A marker class outside the package {@code com.johnowl.gateway}. Its package name only starts with the
 * same letters. The startup check must reject it.
 */
public class OutsideAudienceAuthorization implements AudienceAuthorizationActive {
}
