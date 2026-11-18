package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Each operation has at least one tag, and each tag is a {@code name} of the root {@code tags} list. The comparison
 * is case-sensitive. The rule reads the tags of the raw operation, because swagger-parser drops tags that are not
 * strings, and then the index of a tag in the model is not its index in the file.
 */
public final class OperationTagsDefinedRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "operation-tags-defined", Category.DOCUMENTATION, Severity.WARN,
            "Each operation has at least one tag, and each tag is a name in the root tags list (case-sensitive).",
            "Documentation tools group operations by tag. A tag that the root tags list does not declare has no"
                    + " description and no defined order.");

    /**
     * The root tags of the last file. The engine checks all operations of a file before the next file, so the rule
     * reads the root tags list once for each file, not once for each operation. The record is immutable, so a rule
     * that two threads share stays correct: at worst, both threads read the list. {@link BuiltInRules} shares one
     * instance of the rule, so the record keeps only a weak reference to the raw tree.
     */
    private volatile RootTags last;

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        JsonNode tags = tags(context);
        if (tags == null || !tags.isArray() || tags.isEmpty()) {
            reporter.report(context.pointer(), "Operation has no tags. Add a tag that the root tags list declares.");
            return;
        }
        RootTags declared = rootTags(context.spec().spec().raw());
        for (int i = 0; i < tags.size(); i++) {
            JsonNode tag = tags.get(i);
            if (tag.isTextual() && declared.names().contains(tag.asText())) {
                continue;
            }
            String shown = PrintableText.printable(tag.isTextual() ? tag.asText() : tag.toString());
            String hint = tag.isTextual() ? declared.similar(tag.asText()) : null;
            reporter.report(context.pointer() + "/tags/" + i, "Tag '" + shown + "' is not declared in the root tags"
                    + " list." + (hint == null ? " Declare it, or use a declared tag."
                            : " Did you mean '" + hint + "'? The comparison is case-sensitive."));
        }
    }

    /**
     * The tags of the raw operation. The loader rejects every path item {@code $ref} and checks that the raw tree and
     * the model have the same operations, so the raw tree has each operation that the engine visits.
     */
    private static JsonNode tags(OperationContext context) {
        return context.spec().spec().raw().at(context.pointer()).get("tags");
    }

    private RootTags rootTags(JsonNode raw) {
        RootTags cached = last;
        if (cached != null && cached.raw().get() == raw) {
            return cached;
        }
        RootTags read = RootTags.of(raw);
        last = read;
        return read;
    }

    /**
     * The root tags list of one file.
     *
     * @param raw the raw tree of the file, weakly referenced
     * @param names the names, in document order
     * @param byCase the first name in document order for each name, without regard to case
     */
    private record RootTags(WeakReference<JsonNode> raw, Set<String> names, Map<String, String> byCase) {

        static RootTags of(JsonNode raw) {
            Set<String> names = new LinkedHashSet<>();
            JsonNode tags = raw.get("tags");
            if (tags != null && tags.isArray()) {
                for (JsonNode tag : tags) {
                    JsonNode name = tag.get("name");
                    if (name != null && name.isTextual()) {
                        names.add(name.asText());
                    }
                }
            }
            // CASE_INSENSITIVE_ORDER compares as String.equalsIgnoreCase does.
            Map<String, String> byCase = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            names.forEach(name -> byCase.putIfAbsent(name, name));
            return new RootTags(new WeakReference<>(raw), Set.copyOf(names), byCase);
        }

        /** The first declared name that differs from {@code tag} only in case, or {@code null}. */
        String similar(String tag) {
            String name = byCase.get(tag);
            return name == null ? null : PrintableText.printable(name);
        }
    }
}
