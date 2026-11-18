package com.johnowl.gateway.core.rules;

import java.util.Locale;

/** Media type names of an OAS {@code content} map (specs 030 and 031). */
final class MediaTypes {

    private MediaTypes() {
    }

    /**
     * The media type without parameters, in lowercase. Example: {@code Application/Problem+JSON; charset=utf-8}
     * gives {@code application/problem+json}.
     */
    static String essence(String mediaType) {
        int parameters = mediaType.indexOf(';');
        String type = parameters < 0 ? mediaType : mediaType.substring(0, parameters);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    /** {@code application/json}, or a media type that ends with {@code +json}, ignoring parameters and case. */
    static boolean isJson(String mediaType) {
        String essence = essence(mediaType);
        return essence.equals("application/json") || essence.endsWith("+json");
    }
}
