package com.johnowl.gateway.core.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

class PathSyntaxTest {

    static final String[] ACCEPTED = {
        "/", "/users", "/users/{id}", "/users/me", "/a/{x}/b/{y_2}", "/files/{file-name}", "/files/{_id}",
        "/v1.0/x-y", "/a~b/c_d", "/A/Z/0/9"
    };

    @ParameterizedTest
    @FieldSource("ACCEPTED")
    void acceptsOasPathTemplates(String path) {
        assertThat(PathSyntax.problem(path)).isEmpty();
    }

    /**
     * Each accepted path has the same meaning in Spring PathPattern: it parses, and it matches the request that
     * has a literal value in place of each variable, and no longer request.
     */
    @ParameterizedTest
    @FieldSource("ACCEPTED")
    void acceptedPathsParseAndMatchInPathPattern(String path) {
        PathPattern pattern = PathPatternParser.defaultInstance.parse(path);
        String request = PathSyntax.normalize(path).replace("{}", "value");

        assertThat(pattern.matches(PathContainer.parsePath(request))).isTrue();
        assertThat(pattern.matches(PathContainer.parsePath(request.equals("/") ? "/more" : request + "/more")))
                .isFalse();
        assertThat(pattern.getPatternString()).isEqualTo(path);
    }

    /** PathPattern itself rejects a variable name that is used twice. */
    @Test
    void pathPatternRejectsRepeatedVariable() {
        assertThatExceptionOfType(PatternParseException.class)
                .isThrownBy(() -> PathPatternParser.defaultInstance.parse("/a/{id}/b/{id}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/files/{*path}      | Path '/files/{*path}' uses unsupported syntax '{*...}'.",
        "/users/{id:\\d+}    | Path '/users/{id:\\d+}' uses unsupported syntax '{...:regex}'.",
        "/files/**           | Path '/files/**' uses unsupported syntax '**'.",
        "/files/*            | Path '/files/*' uses unsupported syntax '*'.",
        "/users/{id          | Path '/users/{id' cannot be parsed: unbalanced '{' or '}'.",
        "/users/id}          | Path '/users/id}' cannot be parsed: unbalanced '{' or '}'.",
        "/files/{name}.json  | Path '/files/{name}.json' uses unsupported syntax: a variable must be a whole segment.",
        "/orders/            | Path '/orders/' has an empty segment. Remove the trailing or double slash.",
        "/a//b               | Path '/a//b' has an empty segment. Remove the trailing or double slash.",
        "users               | Path 'users' must start with '/'.",
        "/files/{file.name}  | Path '/files/{file.name}' has an invalid variable name 'file.name'. Use letters, digits, '_' and '-', and do not start with a digit.",
        "/files/{1st}        | Path '/files/{1st}' has an invalid variable name '1st'. Use letters, digits, '_' and '-', and do not start with a digit.",
        "/a/{id}/b/{id}      | Path '/a/{id}/b/{id}' uses the variable name 'id' twice.",
        "'/x,/orders/{id}'   | Path '/x,/orders/{id}' has the unsupported character ','. Literal segments use only letters, digits, '-', '.', '_' and '~'.",
        "/orders/?xport      | Path '/orders/?xport' has the unsupported character '?'. Literal segments use only letters, digits, '-', '.', '_' and '~'.",
        "/a;b                | Path '/a;b' has the unsupported character ';'. Literal segments use only letters, digits, '-', '.', '_' and '~'.",
        "/files/report%202024 | Path '/files/report%202024' has the unsupported character '%'. Literal segments use only letters, digits, '-', '.', '_' and '~'.",
        "/a%2Fb              | Path '/a%2Fb' has the unsupported character '%'. Literal segments use only letters, digits, '-', '.', '_' and '~'.",
        "/a/./b              | Path '/a/./b' has the dot segment '.'.",
        "/a/../b             | Path '/a/../b' has the dot segment '..'.",
        "/caf\u00e9          | Path '/caf\u00e9' has the unsupported character '\\u00E9'. Literal segments use only letters, digits, '-', '.', '_' and '~'."
    })
    void rejectsUnsupportedSyntax(String path, String message) {
        assertThat(PathSyntax.problem(path)).contains(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a b", "/a\tb", "/a\nb", "/a\u0000b", "/a\\b", "/a\"b", "/a<b", "/a@b", "/a+b", "/a=b"})
    void rejectsWhitespaceControlAndReservedCharacters(String path) {
        assertThat(PathSyntax.problem(path)).hasValueSatisfying(
                message -> assertThat(message).contains("has the unsupported character"));
    }

    @ParameterizedTest
    @CsvSource({"/users/{id}, /users/{}", "/a/{x}/b/{y}, /a/{}/b/{}", "/users/me, /users/me"})
    void normalizeReplacesVariables(String path, String normalized) {
        assertThat(PathSyntax.normalize(path)).isEqualTo(normalized);
    }
}
