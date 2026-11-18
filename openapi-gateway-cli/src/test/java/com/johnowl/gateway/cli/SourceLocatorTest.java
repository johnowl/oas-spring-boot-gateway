package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SourceLocatorTest {

    private static final Path YAML = Path.of("src/test/resources/source-locator/orders.yaml");
    private static final Path JSON = Path.of("src/test/resources/source-locator/orders.json");

    private final SourceLocator locator = new SourceLocator();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "''                                    | 1  | 1",
        "/paths/~1orders~1{id}                 | 4  | 3",
        "/paths/~1orders~1{id}/get             | 5  | 5",
        "/paths/~1orders~1{id}/get/parameters/1 | 10 | 11",
        "/paths/~1orders~1{id}/get/parameters/1/in | 11 | 11",
        "/x-gateway                            | 12 | 1",
        "/info/version                         | 2  | 23",
        "/a~0b                                 | 14 | 1"
    })
    void locatesYamlKeysAndArrayElements(String pointer, int line, int column) {
        assertThat(locator.locate(YAML, pointer)).contains(new SourcePosition(line, column));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "''                                    | 1  | 1",
        "/paths/~1orders~1{id}                 | 5  | 5",
        "/paths/~1orders~1{id}/get             | 6  | 7",
        "/paths/~1orders~1{id}/get/parameters/1 | 10 | 11",
        "/x-gateway                            | 15 | 3",
        "/a~0b                                 | 18 | 3"
    })
    void locatesJsonKeysAndArrayElements(String pointer, int line, int column) {
        assertThat(locator.locate(JSON, pointer)).contains(new SourcePosition(line, column));
    }

    @Test
    void missingPointerUsesNearestParent() {
        assertThat(locator.locate(YAML, "/paths/~1orders~1{id}/get/x-gateway-authorization/any-of"))
                .contains(new SourcePosition(5, 5));
        assertThat(locator.locate(YAML, "/components/schemas/Order")).contains(new SourcePosition(1, 1));
    }

    @Test
    void pointerWithoutLeadingSlashUsesTheDocumentRoot() {
        assertThat(locator.locate(YAML, "x-gateway")).contains(new SourcePosition(1, 1));
        assertThat(locator.locate(YAML, "x-gateway/upstream")).contains(new SourcePosition(1, 1));
    }

    /** The oas-parse violation of a file that does not parse uses the root pointer. */
    @Test
    void unparsableFileHasOnlyTheLocationOfTheParseError() {
        Path broken = Path.of("src/test/resources/source-locator/broken.yaml");

        assertThat(locator.locate(broken, "")).contains(new SourcePosition(4, 12));
        assertThat(locator.locate(broken, "/info")).contains(new SourcePosition(4, 12));
    }

    @Test
    void missingFileHasNoPosition() {
        assertThat(locator.locate(Path.of("does-not-exist.yaml"), "")).isEmpty();
    }
}
