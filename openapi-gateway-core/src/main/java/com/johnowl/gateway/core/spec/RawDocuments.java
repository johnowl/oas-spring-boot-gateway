package com.johnowl.gateway.core.spec;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * Reads the raw tree of an OAS document. Rejects content that is too large, content after the first document, keys
 * that are too long, and YAML features that the raw parser (Jackson) and swagger-parser (SnakeYAML) read in
 * different ways: anchors, aliases, merge keys, and explicit tags.
 */
final class RawDocuments {

    /** The maximum size of one file, in bytes (5 MB). */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    /**
     * The maximum size of YAML content, in characters (Unicode code points): 3 MB. swagger-parser reads YAML with
     * this limit of SnakeYAML ({@code DeserializationUtils.Options.maxYamlCodePoints}), so a larger YAML file
     * cannot load (QA-69-7).
     */
    static final int MAX_YAML_CODE_POINTS = 3 * 1024 * 1024;

    /**
     * The maximum length of a key, in characters. A JSON pointer contains the keys of the path to a node, and the
     * rules and the messages copy pointers. A shorter key keeps this work small (SEC-69-1).
     */
    static final int MAX_KEY_LENGTH = 1024;

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /** Jackson stops a key at 50,000 characters with its own message. The loader check gives one message. */
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNameLength((int) MAX_BYTES).build())
            .build()).build();

    /** The own limit of the loader runs first, so the code point limit of the parsers never stops a file. */
    private static final LoaderOptions YAML_OPTIONS = yamlOptions();

    // rebuild() keeps the default parser features. YAMLFactory.builder() has none, and then an empty value is "".
    private static final YAMLMapper YAML = YAMLMapper.builder(
            new YAMLFactory().rebuild().loaderOptions(YAML_OPTIONS).build()).build();

    private RawDocuments() {
    }

    /** Reads a file as UTF-8. Checks the size before the file is read. */
    static String read(Path file) throws IOException, InvalidDocumentException {
        checkSize(Files.size(file));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Parses the content as JSON if the file name ends with {@code .json} or the content starts with <code>{</code>
     * (swagger-parser reads such content as JSON), else as YAML.
     */
    static JsonNode parse(String path, String content) throws InvalidDocumentException {
        checkSize(content.getBytes(StandardCharsets.UTF_8).length);
        if (!startsWithBrace(content)) {
            checkYamlSize(content); // swagger-parser reads this content as YAML, also in a .json file.
        }
        if (isJson(path, content)) {
            return parseJson(path, content);
        }
        JsonNode raw;
        try {
            raw = YAML.readTree(content);
        } catch (JacksonException e) {
            throw new InvalidDocumentException("", syntaxError(false, e));
        }
        rejectAmbiguousYaml(content);
        return raw;
    }

    /** The same test as swagger-parser ({@code DeserializationUtils.isJson}), and the file name. */
    static boolean isJson(String path, String content) {
        return isJsonName(path) || startsWithBrace(content);
    }

    private static boolean isJsonName(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** The test of swagger-parser: it reads this content as JSON, and other content as YAML. */
    private static boolean startsWithBrace(String content) {
        return content.trim().startsWith("{");
    }

    /**
     * Jackson and swagger-parser read only the first JSON value and ignore the rest of the content. A reviewer can
     * then read an operation that does not run. The loader therefore rejects any content after the first value.
     * One byte order mark at the start is allowed (QA-69-3). Jackson does not skip it in a String.
     */
    private static JsonNode parseJson(String path, String content) throws InvalidDocumentException {
        int start = content.isEmpty() || content.charAt(0) != BYTE_ORDER_MARK ? 0 : 1;
        String json = content.substring(start);
        JsonNode raw;
        int end;
        try (JsonParser parser = JSON.createParser(json)) {
            raw = JSON.readTree(parser);
            end = start + (int) parser.currentLocation().getCharOffset();
        } catch (JacksonException e) {
            throw new InvalidDocumentException("", syntaxError(true, e));
        } catch (IOException e) {
            throw new IllegalStateException(e); // A String source does not do I/O.
        }
        rejectContentAfterTheValue(content, end, !isJsonName(path));
        rejectLongJsonKeys(json);
        return raw == null ? MissingNode.getInstance() : raw; // Empty content, as readTree(String).
    }

    /**
     * Allows only white space after the JSON value. A file whose name does not end with {@code .json} is often read
     * as YAML by a reviewer and by an editor, so there the loader also allows comments ({@code #} to the end of the
     * line) and one line with the YAML document end marker {@code ...} (QA-69-2). The parsers ignore both.
     */
    private static void rejectContentAfterTheValue(String content, int end, boolean yamlRules)
            throws InvalidDocumentException {
        boolean lineStart = false;
        boolean documentEnd = false;
        int i = end;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                lineStart = true;
                i++;
            } else if (c == ' ' || c == '\t') {
                i++;
            } else if (yamlRules && c == '#') {
                i = lineEnd(content, i);
            } else if (yamlRules && lineStart && !documentEnd && content.startsWith("...", i)
                    && isBlank(content, i + 3, lineEnd(content, i))) {
                documentEnd = true;
                lineStart = false;
                i += 3;
            } else {
                throw new InvalidDocumentException("", "Invalid JSON at " + lineAndColumn(content, i)
                        + ": content after the first JSON value is not allowed");
            }
        }
    }

    /** The offset of the line break after the offset, or the end of the content. */
    private static int lineEnd(String content, int offset) {
        int i = offset;
        while (i < content.length() && content.charAt(i) != '\n' && content.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static boolean isBlank(String content, int from, int to) {
        for (int i = from; i < to; i++) {
            if (content.charAt(i) != ' ' && content.charAt(i) != '\t') {
                return false;
            }
        }
        return true;
    }

    /** Rejects a key longer than {@value #MAX_KEY_LENGTH} characters in the first JSON value (SEC-69-1). */
    private static void rejectLongJsonKeys(String json) throws InvalidDocumentException {
        try (JsonParser parser = JSON.createParser(json)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.FIELD_NAME && parser.currentName().length() > MAX_KEY_LENGTH) {
                    JsonLocation location = parser.currentTokenLocation();
                    String mapping = parser.getParsingContext().getParent().pathAsPointer().toString();
                    throw new InvalidDocumentException(mapping, "Invalid JSON at line " + location.getLineNr()
                            + ", column " + location.getColumnNr() + ": " + longKey(parser.currentName()));
                }
                if (parser.getParsingContext().inRoot()) {
                    return; // The end of the first value. The content after it is not JSON in a .yaml file.
                }
            }
        } catch (JacksonException e) {
            throw new IllegalStateException(e); // The first read of the same content had no error.
        } catch (IOException e) {
            throw new IllegalStateException(e); // A String source does not do I/O.
        }
    }

    private static String longKey(String key) {
        return "a key has " + key.length() + " characters. The maximum is " + MAX_KEY_LENGTH + " characters";
    }

    /** Line and column as an editor shows them: CR LF, LF, and a lone CR end a line. A byte order mark is skipped. */
    private static String lineAndColumn(String content, int offset) {
        int line = 1;
        int lineStart = !content.isEmpty() && content.charAt(0) == BYTE_ORDER_MARK ? 1 : 0;
        for (int i = 0; i < offset; i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r' && (i + 1 >= content.length() || content.charAt(i + 1) != '\n')) {
                line++;
                lineStart = i + 1;
            }
        }
        return "line " + line + ", column " + (offset - lineStart + 1);
    }

    private static void checkSize(long bytes) throws InvalidDocumentException {
        if (bytes > MAX_BYTES) {
            throw new InvalidDocumentException("",
                    "The file has " + bytes + " bytes. The maximum is " + MAX_BYTES + " bytes (5 MB)");
        }
    }

    private static void checkYamlSize(String content) throws InvalidDocumentException {
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints > MAX_YAML_CODE_POINTS) {
            throw new InvalidDocumentException("", "The YAML file has " + codePoints + " characters. The maximum is "
                    + MAX_YAML_CODE_POINTS + " characters (3 MB)");
        }
    }

    private static LoaderOptions yamlOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit((int) MAX_BYTES);
        return options;
    }

    /**
     * Rejects anchors, aliases, merge keys ({@code <<}) and explicit tags. SnakeYAML (in swagger-parser) expands
     * them, and Jackson does not. Then the raw tree and the model would show different documents. Also rejects a
     * second document ({@code ---}): Jackson reads only the first one. Also rejects a key longer than
     * {@value #MAX_KEY_LENGTH} characters (SEC-69-1).
     */
    private static void rejectAmbiguousYaml(String content) throws InvalidDocumentException {
        Deque<Frame> frames = new ArrayDeque<>();
        boolean document = false;
        try {
            for (Event event : new Yaml(YAML_OPTIONS).parse(new StringReader(content))) {
                if (event instanceof DocumentStartEvent start) {
                    if (document) {
                        throw new InvalidDocumentException("", "Invalid YAML at line "
                                + (start.getStartMark().getLine() + 1) + ", column "
                                + (start.getStartMark().getColumn() + 1) + ": only one YAML document is allowed");
                    }
                    document = true;
                    continue;
                }
                if (event instanceof CollectionEndEvent) {
                    frames.pop();
                    continue;
                }
                if (!(event instanceof NodeEvent node)) {
                    continue;
                }
                Frame parent = frames.peek();
                boolean key = parent != null && parent.isKey();
                if (key && node instanceof ScalarEvent scalar && scalar.getValue().length() > MAX_KEY_LENGTH) {
                    throw new InvalidDocumentException(parent.pointer.toString(), "Invalid YAML at line "
                            + (scalar.getStartMark().getLine() + 1) + ", column "
                            + (scalar.getStartMark().getColumn() + 1) + ": " + longKey(scalar.getValue()));
                }
                RawPointer pointer = parent == null ? RawPointer.root() : parent.childPointer(node);
                String feature = ambiguousFeature(node, key);
                if (feature != null) {
                    throw new InvalidDocumentException(pointer.toString(), "YAML " + feature + " are not allowed");
                }
                if (event instanceof CollectionStartEvent) {
                    frames.push(new Frame(event instanceof MappingStartEvent, pointer));
                }
            }
        } catch (YAMLException e) {
            throw new InvalidDocumentException("", "Invalid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static String ambiguousFeature(NodeEvent node, boolean key) {
        if (node instanceof AliasEvent) {
            return "aliases";
        }
        if (node.getAnchor() != null) {
            return "anchors";
        }
        String tag = switch (node) {
            case ScalarEvent scalar -> scalar.getTag();
            case CollectionStartEvent collection -> collection.getTag();
            default -> null;
        };
        if (tag != null) {
            return "tags";
        }
        if (key && node instanceof ScalarEvent scalar && scalar.getValue().equals("<<")) {
            return "merge keys";
        }
        return null;
    }

    /** One open mapping or sequence, to build the JSON pointer of each node. The text is built only for an error. */
    private static final class Frame {

        private final boolean mapping;
        private final RawPointer pointer;
        private boolean key = true;
        private String currentKey = "";
        private int index = -1;

        Frame(boolean mapping, RawPointer pointer) {
            this.mapping = mapping;
            this.pointer = pointer;
        }

        boolean isKey() {
            return mapping && key;
        }

        /** Moves to the next child and returns its pointer. A key has the pointer of its value. */
        RawPointer childPointer(NodeEvent node) {
            if (!mapping) {
                index++;
                return pointer.child(index);
            }
            if (key) {
                currentKey = node instanceof ScalarEvent scalar ? scalar.getValue() : "";
            }
            key = !key;
            return pointer.child(currentKey);
        }
    }

    /** One line, so that every output format can show it: "Invalid YAML at line 4, column 12: ...". */
    private static String syntaxError(boolean json, JacksonException e) {
        String format = json ? "JSON" : "YAML";
        String reason = withoutToken(
                e.getOriginalMessage() == null ? "" : e.getOriginalMessage().lines().findFirst().orElse(""));
        JsonLocation location = e.getLocation();
        if (location == null || location.getLineNr() < 1) {
            return "Invalid " + format + ": " + reason;
        }
        return "Invalid " + format + " at line " + location.getLineNr()
                + ", column " + location.getColumnNr() + ": " + reason;
    }

    /**
     * Removes the text of an unknown token from the reason. The token can be any content of the file, for example
     * a secret, and the message goes to a CI log. Line and column locate the token.
     */
    private static String withoutToken(String reason) {
        String start = "Unrecognized token '";
        if (!reason.startsWith(start)) {
            return reason;
        }
        int end = reason.indexOf("': was expecting", start.length());
        return "Unrecognized token" + (end < 0 ? "" : reason.substring(end + 1));
    }

    /** The document cannot be used. The pointer is empty for the document root. */
    static final class InvalidDocumentException extends Exception {

        private final String pointer;

        InvalidDocumentException(String pointer, String message) {
            super(message, null, false, false);
            this.pointer = pointer;
        }

        String pointer() {
            return pointer;
        }
    }
}
