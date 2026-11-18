package com.johnowl.gateway.core.suppress;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Violation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The baseline file {@code .oas-baseline.json}: known violations of configurable rules. The
 * fingerprint of a violation is (rule id, file, pointer), without line, severity, or message.
 */
public final class Baseline implements BaselinePolicy {

    public static final int VERSION = 1;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Two spaces and {@code \n} on every operating system, so the file does not change with the platform. */
    private static final ObjectWriter WRITER = JSON.writer(
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));
    private static final Comparator<Entry> ORDER =
            Comparator.comparing(Entry::ruleId).thenComparing(Entry::file).thenComparing(Entry::pointer);

    /** One fingerprint. */
    public record Entry(String ruleId, String file, String pointer) {

        static Entry of(Violation violation) {
            return new Entry(violation.ruleId(), violation.file(), violation.pointer());
        }
    }

    private final Set<Entry> entries;

    private Baseline(Set<Entry> entries) {
        this.entries = entries;
    }

    @Override
    public boolean isKnown(Violation violation) {
        return entries.contains(Entry.of(violation));
    }

    public Set<Entry> entries() {
        return entries;
    }

    /**
     * Reads a baseline file.
     *
     * @param rules the rules that the CLI can run
     * @throws BaselineException for invalid JSON, an unknown version, an unknown rule id, or a rule
     *     with {@code configurable=false}
     */
    public static Baseline read(String path, String json, Map<String, RuleMeta> rules) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new BaselineException(path, List.of("invalid JSON: " + e.getOriginalMessage().lines().findFirst().orElse("")));
        }
        List<String> problems = new ArrayList<>();
        if (root == null || !root.isObject() || !root.path("version").isInt() || root.get("version").asInt() != VERSION) {
            throw new BaselineException(path, List.of("version: must be " + VERSION + "."));
        }
        JsonNode list = root.get("entries");
        if (list == null || !list.isArray()) {
            throw new BaselineException(path, List.of("entries: must be a list."));
        }
        Set<Entry> entries = new TreeSet<>(ORDER);
        for (int i = 0; i < list.size(); i++) {
            JsonNode node = list.get(i);
            if (!node.path("ruleId").isTextual() || !node.path("file").isTextual() || !node.path("pointer").isTextual()) {
                problems.add("entries[" + i + "]: needs ruleId, file, and pointer.");
                continue;
            }
            String ruleId = node.get("ruleId").asText();
            RuleMeta rule = rules.get(ruleId);
            if (rule == null) {
                problems.add("entries[" + i + "]: unknown rule id '" + ruleId + "'.");
            } else if (!rule.configurable()) {
                problems.add("entries[" + i + "]: rule '" + ruleId + "' cannot be in a baseline.");
            } else {
                entries.add(new Entry(ruleId, node.get("file").asText(), node.get("pointer").asText()));
            }
        }
        if (!problems.isEmpty()) {
            throw new BaselineException(path, problems);
        }
        return new Baseline(entries);
    }

    /**
     * Writes the baseline of the violations of configurable rules: sorted by rule id, file, and
     * pointer, without duplicates.
     */
    public static String write(List<Violation> violations, Map<String, RuleMeta> rules) {
        Set<Entry> entries = new TreeSet<>(ORDER);
        for (Violation violation : violations) {
            RuleMeta rule = rules.get(violation.ruleId());
            if (rule != null && rule.configurable()) {
                entries.add(Entry.of(violation));
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode list = root.putArray("entries");
        for (Entry entry : entries) {
            list.addObject().put("ruleId", entry.ruleId()).put("file", entry.file()).put("pointer", entry.pointer());
        }
        try {
            return WRITER.writeValueAsString(root) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
