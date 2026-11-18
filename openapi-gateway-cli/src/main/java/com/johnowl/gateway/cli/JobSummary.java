package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.access.AccessChange;
import com.johnowl.gateway.core.access.AudienceBinding;
import java.util.List;

/**
 * The Markdown tables of the loosening changes and of the audience bindings for the job summary of GitHub Actions.
 * See specs/034-access-change-review.md, "Job summary".
 *
 * <p>The values come from the files under review. Each value is in a {@link #code code span}, so that Markdown, a
 * link, or HTML in a value has no effect.
 */
final class JobSummary {

    private JobSummary() {
    }

    /**
     * A heading, one sentence, and the table with one row for each loosening change. GitHub shows only the first
     * annotations of a step, so the summary is the complete list for the reviewer (SEC-75-3).
     */
    static String changes(List<AccessChange> changes) {
        StringBuilder markdown = new StringBuilder("### Access loosening changes\n\n")
                .append("Each change makes an operation easier to call. Check that each change is intended.\n\n")
                .append("| Change | Method | Public path | Details | File | Pointer |\n")
                .append("|---|---|---|---|---|---|\n");
        for (AccessChange change : changes) {
            markdown.append("| ").append(change.kind().id())
                    .append(" | ").append(code(change.method()))
                    .append(" | ").append(code(change.publicPath()))
                    .append(" | ").append(code(change.message()))
                    .append(" | ").append(code(change.file()))
                    .append(" | ").append(code(change.pointer()))
                    .append(" |\n");
        }
        return markdown.append('\n').toString();
    }

    /** A heading, one sentence, and the table with one row for each binding. */
    static String bindings(List<AudienceBinding> bindings) {
        StringBuilder markdown = new StringBuilder("### Audience bindings\n\n")
                .append("The gateway sends the token of each audience to its upstream."
                        + " Check that each upstream is the backend of the audience.\n\n")
                .append("| Change | Audience | Upstream (base) | Upstream (head) | Prefix (base) | Prefix (head)"
                        + " | Upstream base path (base) | Upstream base path (head) | File |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (AudienceBinding binding : bindings) {
            boolean isNew = binding.kind() == AudienceBinding.Kind.NEW;
            markdown.append("| ").append(binding.kind().id())
                    .append(" | ").append(optional(binding.audience()))
                    .append(" | ").append(isNew ? "none" : code(binding.baseUpstream()))
                    .append(" | ").append(code(binding.headUpstream()))
                    .append(" | ").append(optional(binding.basePrefix()))
                    .append(" | ").append(optional(binding.headPrefix()))
                    .append(" | ").append(optional(binding.baseUpstreamBasePath()))
                    .append(" | ").append(optional(binding.headUpstreamBasePath()))
                    .append(" | ").append(code(binding.file()))
                    .append(" |\n");
        }
        return markdown.append('\n').toString();
    }

    /** {@code none} for a missing value, the value in a code span otherwise. */
    private static String optional(String value) {
        return value == null ? "none" : code(value);
    }

    /**
     * The value in a code span. Control characters, line separators, and bidirectional controls become escapes
     * ({@link ConsoleText#printable}), so the value stays in its table row. The delimiter is one backtick longer than
     * the longest run of backticks in the value, because a backslash cannot escape a backtick in a code span. One
     * space separates the value from each delimiter. Each {@code |} becomes {@code \|}, so the value cannot add a
     * table column; GitHub removes that backslash also inside a code span in a table.
     */
    static String code(String value) {
        String text = ConsoleText.printable(value).replace("|", "\\|");
        String delimiter = "`".repeat(longestBacktickRun(text) + 1);
        return delimiter + " " + text + " " + delimiter;
    }

    private static int longestBacktickRun(String text) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
