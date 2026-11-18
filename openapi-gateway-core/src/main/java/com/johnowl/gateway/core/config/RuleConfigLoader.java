package com.johnowl.gateway.core.config;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.Severity;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads {@code .oas-gateway.yaml} and checks it against the rules that the CLI can run. */
public final class RuleConfigLoader {

    /** A repeated key is a problem: otherwise the last value wins without a message. */
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Limits for {@code ignore-files} globs. The JDK turns a glob into a regex that can backtrack a lot. */
    static final int MAX_GLOB_LENGTH = 200;
    static final int MAX_GLOB_WILDCARDS = 4;
    private static final Pattern WILDCARD = Pattern.compile("\\*+");
    private static final Set<String> ROOT_KEYS = Set.of("extends", "rules", "ignore-files");
    private static final Set<String> RULE_KEYS = Set.of("severity", "options");

    private final Map<String, Rule> registry;

    /** @param registry all rules that the CLI can run, CONTRACT rules included */
    public RuleConfigLoader(List<Rule> registry) {
        this.registry = registry.stream().collect(Collectors.toMap(rule -> rule.meta().id(), Function.identity()));
    }

    /**
     * @param path the location that messages show
     * @param baseDirectory the directory of the file; {@code ignore-files} globs are relative to it
     * @throws RuleConfigException with all problems of the file
     */
    public RuleConfig load(String path, String content, Path baseDirectory) {
        JsonNode root;
        try {
            root = YAML.readTree(content);
        } catch (JacksonException e) {
            JsonLocation location = e.getLocation();
            String duplicate = duplicateKey(e);
            if (duplicate != null) {
                throw new RuleConfigException(path, List.of(duplicate + ": duplicate key"
                        + (location == null ? "" : " at line " + location.getLineNr()) + "."));
            }
            String where = location == null ? "" : " at line " + location.getLineNr() + ", column " + location.getColumnNr();
            throw new RuleConfigException(path, List.of("invalid YAML" + where + ": "
                    + e.getOriginalMessage().lines().findFirst().orElse("")));
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new RuleConfig(Profile.RECOMMENDED, Map.of(), Map.of(), baseDirectory, Map.of());
        }
        List<String> problems = new ArrayList<>();
        if (!root.isObject()) {
            throw new RuleConfigException(path, List.of("the file must contain a YAML object."));
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!ROOT_KEYS.contains(key)) {
                problems.add(key + ": unknown key. Allowed: extends, rules, ignore-files.");
            }
        }
        Profile profile = profile(root.get("extends"), problems);
        Map<String, Optional<Severity>> overrides = new HashMap<>();
        Map<String, RuleOptions> options = new HashMap<>();
        rules(root.get("rules"), overrides, options, problems);
        Map<String, PathMatcher> ignoreFiles = ignoreFiles(root.get("ignore-files"), problems);
        if (!problems.isEmpty()) {
            throw new RuleConfigException(path, problems);
        }
        return new RuleConfig(profile, overrides, options, baseDirectory, ignoreFiles);
    }

    /** Returns the dotted path of a repeated key, for example {@code rules.path-kebab-case}, or {@code null}. */
    private static String duplicateKey(JacksonException e) {
        if (!(e.getProcessor() instanceof JsonParser parser) || !e.getOriginalMessage().startsWith("Duplicate field")) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonStreamContext context = parser.getParsingContext(); context != null; context = context.getParent()) {
            if (context.inObject() && context.getCurrentName() != null) {
                names.addFirst(context.getCurrentName());
            }
        }
        return String.join(".", names);
    }

    /** An empty value, for example {@code rules:} with all entries commented out, is the same as no key. */
    private static boolean isUnset(JsonNode node) {
        return node == null || node.isNull();
    }

    private static Profile profile(JsonNode node, List<String> problems) {
        if (isUnset(node)) {
            return Profile.RECOMMENDED;
        }
        for (Profile profile : Profile.values()) {
            if (node.isTextual() && profile.fileName().equals(node.asText())) {
                return profile;
            }
        }
        problems.add("extends: must be recommended or strict.");
        return Profile.RECOMMENDED;
    }

    private void rules(JsonNode node, Map<String, Optional<Severity>> overrides, Map<String, RuleOptions> options,
            List<String> problems) {
        if (isUnset(node)) {
            return;
        }
        if (!node.isObject()) {
            problems.add("rules: must be a map of rule id to rule setting.");
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String id = entry.getKey();
            String where = "rules." + id;
            Rule rule = registry.get(id);
            if (rule == null) {
                problems.add(where + ": unknown rule id.");
                continue;
            }
            if (!rule.meta().configurable()) {
                problems.add(where + ": rule '" + id + "' is "
                        + (rule.meta().category() == Category.CONTRACT ? "a CONTRACT rule" : "not configurable")
                        + " and cannot be configured.");
                continue;
            }
            JsonNode setting = entry.getValue();
            if (setting.isTextual() || isOff(setting)) {
                severity(setting, where, problems).ifPresent(severity -> overrides.put(id, severity));
            } else if (setting.isObject()) {
                if (setting.isEmpty()) {
                    problems.add(where + ": the setting is empty. Use severity, options, or both.");
                    continue;
                }
                for (Iterator<String> keys = setting.fieldNames(); keys.hasNext(); ) {
                    String key = keys.next();
                    if (!RULE_KEYS.contains(key)) {
                        problems.add(where + "." + key + ": unknown key. Allowed: severity, options.");
                    }
                }
                if (setting.has("severity")) {
                    severity(setting.get("severity"), where + ".severity", problems)
                            .ifPresent(severity -> overrides.put(id, severity));
                }
                if (setting.has("options")) {
                    options.put(id, options(rule, setting.get("options"), where + ".options", problems));
                }
            } else {
                problems.add(where + ": must be a severity or an object with severity and options.");
            }
        }
    }

    /** Returns empty if the value is not valid; returns {@code Optional.of(Optional.empty())} for {@code off}. */
    private static Optional<Optional<Severity>> severity(JsonNode node, String where, List<String> problems) {
        String value = isOff(node) ? "off" : node.isTextual() ? node.asText() : "";
        return switch (value) {
            case "off" -> Optional.of(Optional.empty());
            case "info", "warn", "error" -> Optional.of(Optional.of(Severity.valueOf(value.toUpperCase(Locale.ROOT))));
            default -> {
                problems.add(where + ": severity must be off, info, warn, or error.");
                yield Optional.empty();
            }
        };
    }

    /** YAML 1.1 reads an unquoted {@code off} as the boolean {@code false}. */
    private static boolean isOff(JsonNode node) {
        return node.isBoolean() && !node.asBoolean();
    }

    private static RuleOptions options(Rule rule, JsonNode node, String where, List<String> problems) {
        Map<String, OptionType> declared = rule instanceof RuleWithOptions withOptions ? withOptions.optionTypes() : Map.of();
        if (!node.isObject()) {
            problems.add(where + ": must be a map of option name to value.");
            return RuleOptions.none();
        }
        Map<String, Object> values = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> option = it.next();
            OptionType type = declared.get(option.getKey());
            String optionWhere = where + "." + option.getKey();
            if (type == null) {
                problems.add(optionWhere + ": unknown option" + (declared.isEmpty()
                        ? ". The rule has no options."
                        : ". Allowed: " + String.join(", ", declared.keySet().stream().sorted().toList()) + "."));
                continue;
            }
            switch (type) {
                case STRING_LIST -> {
                    JsonNode value = option.getValue();
                    List<String> strings = new ArrayList<>();
                    boolean valid = value.isArray() && !value.isEmpty();
                    if (valid) {
                        for (JsonNode item : value) {
                            valid &= item.isTextual();
                            strings.add(item.asText());
                        }
                    }
                    if (valid) {
                        values.put(option.getKey(), List.copyOf(strings));
                    } else {
                        problems.add(optionWhere + ": must be a non-empty list of strings.");
                    }
                }
            }
        }
        return new RuleOptions(values);
    }

    /** Each glob with its matcher, in the order of the file. */
    private static Map<String, PathMatcher> ignoreFiles(JsonNode node, List<String> problems) {
        Map<String, PathMatcher> matchers = new LinkedHashMap<>();
        if (isUnset(node)) {
            return matchers;
        }
        if (!node.isArray()) {
            problems.add("ignore-files: must be a list of glob patterns.");
            return matchers;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode glob = node.get(i);
            try {
                if (!glob.isTextual()) {
                    throw new IllegalArgumentException("not a string");
                }
                String problem = globProblem(glob.asText());
                if (problem != null) {
                    problems.add("ignore-files[" + i + "]: " + problem);
                    continue;
                }
                matchers.put(glob.asText(), FileSystems.getDefault().getPathMatcher("glob:" + glob.asText()));
            } catch (IllegalArgumentException e) {
                problems.add("ignore-files[" + i + "]: invalid glob: " + e.getMessage().lines().findFirst().orElse(""));
            }
        }
        return matchers;
    }

    /** Returns why the glob is not allowed, or {@code null}. */
    private static String globProblem(String glob) {
        if (glob.length() > MAX_GLOB_LENGTH) {
            return "the glob has " + glob.length() + " characters. The maximum is " + MAX_GLOB_LENGTH + ".";
        }
        long wildcards = WILDCARD.matcher(glob).results().count();
        if (wildcards > MAX_GLOB_WILDCARDS) {
            return "the glob has " + wildcards + " wildcards (* or **). The maximum is " + MAX_GLOB_WILDCARDS + ".";
        }
        // Files outside the directory of the configuration file are never ignored, so such a glob never matches.
        if (glob.startsWith("/") || glob.startsWith("\\") || List.of(glob.split("[/\\\\]")).contains("..")) {
            return "the glob must be relative to the directory of the configuration file and must not contain '..'.";
        }
        return null;
    }
}
