package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each absolute server URL uses {@code https}. The rule checks each Server Object of the raw document: each
 * element of a {@code servers} array, at any depth (root, path items, operations, webhooks, callbacks, and
 * components), and the {@code server} of each Link Object. Server variables are replaced with their
 * {@code default} value first. A URL with a C0 control character, or with whitespace at the start or the end, is
 * a violation, because clients can read it as another URL. The rule reads the raw document, so that the pointer
 * is the {@code url} field in the file.
 */
public final class ServersHttpsOnlyRule implements SpecRule {

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]*)}");

    private static final RuleMeta META = BestPracticeRule.meta(
            "servers-https-only", Category.SECURITY, Severity.ERROR,
            "Each absolute server URL (each Server Object, at any depth, also in links) uses https, after"
                    + " server variables are replaced with their default value. Relative URLs pass. A URL with a"
                    + " control character or with whitespace at the start or the end fails.",
            "An http server URL invites clients to send credentials and data without TLS. The gateway routes"
                    + " with x-gateway.upstream, not with servers.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        RawTree.walk(context.spec().raw(), "", (pointer, name, node) -> {
            if ("servers".equals(name) && node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    checkServer(node.get(i), pointer + "/" + i, reporter);
                }
            } else if ("links".equals(name) && node.isObject()) {
                for (Map.Entry<String, JsonNode> link : node.properties()) {
                    checkServer(link.getValue().path("server"),
                            pointer + "/" + JsonPointers.escape(link.getKey()) + "/server", reporter);
                }
            }
        });
    }

    private static void checkServer(JsonNode server, String pointer, Reporter reporter) {
        JsonNode url = server.get("url");
        if (url == null || !url.isTextual()) {
            return;
        }
        String expanded = expand(url.asText(), server.get("variables"));
        String shown = expanded.equals(url.asText())
                ? "'" + PrintableText.printable(url.asText()) + "'"
                : "'" + PrintableText.printable(url.asText()) + "' (with default values: '"
                        + PrintableText.printable(expanded) + "')";
        Matcher scheme = SCHEME.matcher(schemeText(expanded));
        if (scheme.find() && !scheme.group(1).toLowerCase(Locale.ROOT).equals("https")) {
            reporter.report(pointer + "/url", "Server URL " + shown
                    + " does not use https. Clients can send credentials without TLS.");
        } else if (hasControlOrOuterWhitespace(expanded)) {
            reporter.report(pointer + "/url", "Server URL " + shown + " has a control character, or whitespace at"
                    + " the start or the end. Clients can read it as another URL.");
        }
    }

    /**
     * The text that a URL parser reads the scheme from: without tab, CR, and LF, and without control characters
     * and spaces at the start and the end (WHATWG URL Standard). So {@code " ht\ttp://a"} has the scheme
     * {@code http}.
     */
    static String schemeText(String url) {
        String text = url.replace("\t", "").replace("\r", "").replace("\n", "");
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean hasControlOrOuterWhitespace(String url) {
        return !url.equals(url.strip()) || url.chars().anyMatch(c -> c < ' ');
    }

    /** Replaces each {@code {name}} that has a variable with a text {@code default}. Other text stays. */
    static String expand(String url, JsonNode variables) {
        if (variables == null || !variables.isObject()) {
            return url;
        }
        Matcher variable = VARIABLE.matcher(url);
        StringBuilder result = new StringBuilder();
        while (variable.find()) {
            JsonNode value = variables.path(variable.group(1)).get("default");
            String replacement = value != null && value.isTextual() ? value.asText() : variable.group();
            variable.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        variable.appendTail(result);
        return result.toString();
    }
}
