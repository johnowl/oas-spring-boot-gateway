package com.johnowl.gateway.cli;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.johnowl.gateway.core.validation.JsonPointers;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a JSON pointer to a line and a column in the original YAML or JSON file.
 *
 * <p>Position choice: for a member of an object, the position is the position of its key, because
 * that is the line a reviewer looks for in a pull request. For an array element, the position is
 * the start of the element. For the document root (empty pointer), the position is the start of
 * the document.
 *
 * <p>A pointer that does not exist in the file maps to its nearest existing parent. A pointer that does not start
 * with {@code /} maps to the document root. The pointers
 * use RFC 6901 escapes ({@code ~1} for {@code /}, {@code ~0} for {@code ~}), as violations do.
 *
 * <p>One instance caches the map of each file, because one file often has many violations.
 */
final class SourceLocator {

    private static final JsonFactory JSON = new JsonFactory();
    private static final YAMLFactory YAML = new YAMLFactory();

    private final Map<Path, Map<String, SourcePosition>> cache = new HashMap<>();

    Optional<SourcePosition> locate(Path file, String pointer) {
        Map<String, SourcePosition> positions = cache.computeIfAbsent(file.toAbsolutePath().normalize(), SourceLocator::read);
        String current = pointer;
        while (true) {
            SourcePosition position = positions.get(current);
            if (position != null) {
                return Optional.of(position);
            }
            if (current.isEmpty()) {
                return Optional.empty();
            }
            // A pointer without a leading '/' is not valid. Its nearest parent is the document root.
            int slash = current.lastIndexOf('/');
            current = slash < 0 ? "" : current.substring(0, slash);
        }
    }

    /**
     * Returns an empty map if the file cannot be read or parsed. If the parse error has a location, the map has
     * only the document root, at that location: the {@code oas-parse} violation of the file uses the root pointer.
     */
    private static Map<String, SourcePosition> read(Path file) {
        Map<String, SourcePosition> positions = new HashMap<>();
        JsonFactory factory = file.toString().toLowerCase(Locale.ROOT).endsWith(".json") ? JSON : YAML;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                JsonParser parser = factory.createParser(reader)) {
            if (parser.nextToken() != null) {
                walk(parser, "", positions);
            }
        } catch (JacksonException e) {
            JsonLocation location = e.getLocation();
            return location == null || location.getLineNr() < 1 ? Map.of() : Map.of("", position(location));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        return positions;
    }

    /** The parser is on the first token of the value at {@code pointer}. */
    private static void walk(JsonParser parser, String pointer, Map<String, SourcePosition> positions)
            throws IOException {
        positions.putIfAbsent(pointer, position(parser.currentTokenLocation()));
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT) {
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String child = pointer + "/" + JsonPointers.escape(parser.currentName());
                positions.putIfAbsent(child, position(parser.currentTokenLocation()));
                parser.nextToken();
                walk(parser, child, positions);
            }
        } else if (token == JsonToken.START_ARRAY) {
            int index = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                walk(parser, pointer + "/" + index++, positions);
            }
        }
    }

    private static SourcePosition position(JsonLocation location) {
        return new SourcePosition(location.getLineNr(), location.getColumnNr());
    }
}
