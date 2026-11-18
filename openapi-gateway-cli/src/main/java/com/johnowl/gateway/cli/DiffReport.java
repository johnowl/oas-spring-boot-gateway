package com.johnowl.gateway.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.johnowl.gateway.core.access.AccessChange;
import com.johnowl.gateway.core.access.AccessDiff;
import com.johnowl.gateway.core.access.AudienceBinding;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the result of {@code diff} in the formats of specs/034-access-change-review.md, "Output". The values come
 * from the files under review, so each format escapes them: the text format with {@link ConsoleText#untrusted} on
 * each line, the GitHub format with {@link ConsoleText#printable} and the escapes of workflow commands, the JSON
 * format with {@link RulesCommand.SafeCharacterEscapes}.
 */
final class DiffReport {

    /** Increment on a breaking change to the JSON layout. */
    static final int FORMAT_VERSION = 1;

    private DiffReport() {
    }

    /**
     * Three lines for each change, three lines for each binding, then the summary:
     *
     * <pre>
     * LOOSENING permission-removed-from-all-of DELETE /orders-api/orders/{id}
     *   openapi/orders.yaml#/paths/~1orders~1{id}/delete
     *   Orders.Delete removed from all-of
     * BINDING binding-new audience=6f1c2e8a-0000-0000-0000-000000000000
     *   openapi/orders.yaml#/x-gateway/upstream
     *   base: none; head: upstream=http://orders-service:8080, upstream-base-path=/v1, prefix=/orders-api
     *
     * 1 access loosening change in 1 operation. 1 new or changed audience binding to review.
     * </pre>
     */
    static void writeText(AccessDiff.Result result, PrintWriter out) {
        for (AccessChange change : result.changes()) {
            line(out, "LOOSENING " + change.kind().id() + " " + change.method() + " " + change.publicPath());
            line(out, "  " + change.file() + "#" + change.pointer());
            line(out, "  " + change.message());
        }
        for (AudienceBinding binding : result.bindings()) {
            line(out, "BINDING " + binding.kind().id() + " "
                    + (binding.audience() == null ? "without an audience" : "audience=" + binding.audience()));
            line(out, "  " + binding.file() + "#" + binding.pointer());
            line(out, "  base: " + (binding.baseUpstream() == null ? "none"
                    : target(binding.baseUpstream(), binding.baseUpstreamBasePath(), binding.basePrefix()))
                    + "; head: " + target(binding.headUpstream(), binding.headUpstreamBasePath(), binding.headPrefix()));
        }
        if (!result.changes().isEmpty() || !result.bindings().isEmpty()) {
            out.print("\n");
        }
        line(out, summary(result));
    }

    /**
     * One {@code ::error} line for each change at the operation in the head file, one {@code ::error} line for each
     * binding at the changed field, then the summary line. A binding fails the job like a change (SEC-74-1).
     */
    static void writeGithub(AccessDiff.Result result, SourceLocator locator, PrintWriter out) {
        for (AccessChange change : result.changes()) {
            out.print(annotation("error", change.file(), change.pointer(), locator, "Access loosening ("
                    + change.kind().id() + ") of " + change.method() + " " + change.publicPath() + ": "
                    + change.message() + ".") + "\n");
        }
        for (AudienceBinding binding : result.bindings()) {
            String head = phrase(binding.headUpstream(), binding.headUpstreamBasePath(), binding.headPrefix());
            boolean isNew = binding.kind() == AudienceBinding.Kind.NEW;
            String base = isNew ? "" : " (base: " + phrase(binding.baseUpstream(), binding.baseUpstreamBasePath(),
                    binding.basePrefix()) + ")";
            String message = binding.audience() == null
                    ? (isNew ? "New binding without an audience: the public operations go to "
                            : "Binding without an audience changed: the public operations now go to ")
                            + head + base + ". Check that this upstream is the right backend."
                    : (isNew ? "New audience binding: " + binding.audience() + " sends tokens to "
                            : "Audience binding changed: " + binding.audience() + " now sends tokens to ")
                            + head + base + ". Check that this upstream is the backend of the audience.";
            out.print(annotation("error", binding.file(), binding.pointer(), locator, message) + "\n");
        }
        line(out, summary(result));
    }

    /** The layout of specs/034-access-change-review.md, "JSON format". */
    static void writeJson(AccessDiff.Result result, PrintWriter out) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("formatVersion", FORMAT_VERSION);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "oas-gateway-validate");
        tool.put("version", ManifestVersionProvider.version());
        document.put("tool", tool);

        List<Map<String, Object>> changes = new ArrayList<>();
        for (AccessChange change : result.changes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("changeId", change.kind().id());
            entry.put("method", change.method());
            entry.put("publicPath", change.publicPath());
            entry.put("file", change.file());
            entry.put("pointer", change.pointer());
            entry.put("permission", change.permission());
            entry.put("message", change.message());
            changes.add(entry);
        }
        document.put("changes", changes);

        List<Map<String, Object>> bindings = new ArrayList<>();
        for (AudienceBinding binding : result.bindings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", binding.kind().id());
            entry.put("audience", binding.audience());
            entry.put("baseUpstream", binding.baseUpstream());
            entry.put("headUpstream", binding.headUpstream());
            entry.put("basePrefix", binding.basePrefix());
            entry.put("headPrefix", binding.headPrefix());
            entry.put("baseUpstreamBasePath", binding.baseUpstreamBasePath());
            entry.put("headUpstreamBasePath", binding.headUpstreamBasePath());
            entry.put("file", binding.file());
            entry.put("pointer", binding.pointer());
            bindings.add(entry);
        }
        document.put("bindings", bindings);

        try {
            out.print(RulesCommand.WRITER.writeValueAsString(document) + "\n");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code 2 access loosening changes in 2 operations.}, and the number of bindings if there is one. */
    static String summary(AccessDiff.Result result) {
        String summary = plural(result.changes().size(), "access loosening change") + " in "
                + plural(result.operationCount(), "operation") + ".";
        return result.bindings().isEmpty() ? summary
                : summary + " " + plural(result.bindings().size(), "new or changed audience binding") + " to review.";
    }

    private static String annotation(String command, String file, String pointer, SourceLocator locator,
            String message) {
        StringBuilder line = new StringBuilder("::").append(command).append(" file=")
                .append(GithubReportFormatter.escapeProperty(ConsoleText.printable(file)));
        locator.locate(Path.of(file), pointer).ifPresent(position ->
                line.append(",line=").append(position.line()).append(",col=").append(position.column()));
        return line.append("::").append(GithubReportFormatter.escapeData(ConsoleText.printable(message))).toString();
    }

    /** {@code upstream=http://orders-service:8080, upstream-base-path=/v1, prefix=/orders-api} */
    private static String target(String upstream, String upstreamBasePath, String prefix) {
        return "upstream=" + upstream + (upstreamBasePath == null ? "" : ", upstream-base-path=" + upstreamBasePath)
                + (prefix == null ? "" : ", prefix=" + prefix);
    }

    /** {@code http://orders-service:8080 with the upstream base path /v1 for the prefix /orders-api} */
    private static String phrase(String upstream, String upstreamBasePath, String prefix) {
        return upstream + (upstreamBasePath == null ? "" : " with the upstream base path " + upstreamBasePath)
                + (prefix == null ? " without a prefix" : " for the prefix " + prefix);
    }

    private static void line(PrintWriter out, String text) {
        out.print(ConsoleText.untrusted(text) + "\n");
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
