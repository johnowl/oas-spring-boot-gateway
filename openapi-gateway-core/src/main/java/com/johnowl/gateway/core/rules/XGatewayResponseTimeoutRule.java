package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.math.BigInteger;
import java.util.Map;

/** Checks that {@code x-gateway-response-timeout} is an integer from 100 to 60000 milliseconds. */
public final class XGatewayResponseTimeoutRule implements OperationRule {

    static final String EXTENSION = "x-gateway-response-timeout";

    static final long MIN_MILLIS = 100;
    static final long MAX_MILLIS = 60_000;

    private static final RuleMeta META = new RuleMeta(
            "x-gateway-response-timeout",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "If present, x-gateway-response-timeout is an integer from 100 to 60000 (milliseconds).",
            "The gateway uses this value as the response timeout of the route. A wrong value makes"
                    + " every call fail, or keeps connections open too long.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        Map<String, Object> extensions = context.operation().getExtensions();
        if (extensions == null || !extensions.containsKey(EXTENSION)) {
            return;
        }
        String pointer = context.pointer() + "/" + EXTENSION;
        Object value = extensions.get(EXTENSION);
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)) {
            reporter.report(pointer, "Value must be an integer (milliseconds).");
            return;
        }
        BigInteger millis = new BigInteger(value.toString());
        if (millis.compareTo(BigInteger.valueOf(MIN_MILLIS)) < 0
                || millis.compareTo(BigInteger.valueOf(MAX_MILLIS)) > 0) {
            reporter.report(pointer,
                    "Value " + millis + " is out of range " + MIN_MILLIS + "–" + MAX_MILLIS + " ms.");
        }
    }
}
