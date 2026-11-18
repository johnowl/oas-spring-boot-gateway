package com.johnowl.gateway.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** References, YAML features and size limits that the loader rejects (spec 004, "Safe loading"). */
class SpecLoaderSafetyTest {

    private static final String REF_POINTER =
            "/paths/~1orders/get/responses/500/content/application~1json/schema/$ref";

    private static final String JSON_DOCUMENT =
            "{\"openapi\": \"3.0.3\", \"info\": {\"title\": \"A\", \"version\": \"1\"}, \"paths\": {}}";

    private final SpecLoader loader = new SpecLoader();

    @TempDir
    Path dir;

    @Test
    void rejectsRemoteReferenceWithoutNetworkAccess() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String ref = "http://127.0.0.1:" + server.getLocalPort() + "/common.yaml#/components/schemas/Error";
            Path root = write("api/orders.yaml", withRef(ref));

            SpecLoadResult result = loader.load(root);

            assertThat(result.spec()).isEmpty();
            assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                    .containsExactly(tuple("oas-parse", REF_POINTER,
                            "Reference '" + ref + "' is not allowed: remote references and URLs are not allowed"));
            server.setSoTimeout(200);
            assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            file:///etc/passwd#/x          | remote references and URLs are not allowed
            https://example.com/a.yaml     | remote references and URLs are not allowed
            /etc/common.yaml#/x            | only relative paths without backslashes and percent-encoding are allowed
            ..\\secret.yaml#/x             | only relative paths without backslashes and percent-encoding are allowed
            %2e%2e/secret.yaml#/x          | only relative paths without backslashes and percent-encoding are allowed
            ../secret.yaml#/x              | the file is outside the directory of the OAS file
            sub/../../secret.yaml#/x       | the file is outside the directory of the OAS file
            """)
    void rejectsReferenceThatCanLeaveTheDirectory(String ref, String reason) throws IOException {
        write("secret.yaml", "x: {type: string}\n");
        Path root = write("api/orders.yaml", withRef(ref));

        assertThat(loader.load(root).violations()).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple(REF_POINTER, "Reference '" + ref + "' is not allowed: " + reason));
    }

    @Test
    void rejectsReferenceThroughSymbolicLinkThatLeavesTheDirectory() throws IOException {
        Path secret = write("secret.yaml", "components: {schemas: {Error: {type: string}}}\n");
        Files.createDirectories(dir.resolve("api"));
        Files.createSymbolicLink(dir.resolve("api/common.yaml"), secret);
        Path root = write("api/orders.yaml", withRef("./common.yaml#/components/schemas/Error"));

        assertThat(loader.load(root).violations()).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple(REF_POINTER, "Reference './common.yaml#/components/schemas/Error' is not"
                        + " allowed: the file is outside the directory of the OAS file"));
    }

    @Test
    void rejectsForbiddenReferenceInReferencedFile() throws IOException {
        write("secret.yaml", "Error: {type: string}\n");
        write("api/schemas/common.yaml", """
                components:
                  schemas:
                    Error:
                      $ref: "../../secret.yaml#/Error"
                """);
        Path root = write("api/orders.yaml", withRef("./schemas/common.yaml#/components/schemas/Error"));

        assertThat(loader.load(root).violations()).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple(REF_POINTER, "In referenced file 'schemas/common.yaml': Reference"
                        + " '../../secret.yaml#/Error' is not allowed: the file is outside the directory of the OAS file"));
    }

    @Test
    void rejectsYamlAliasInReferencedFile() throws IOException {
        write("api/common.yaml", """
                components:
                  schemas:
                    Base: &base {type: string}
                    Error: *base
                """);
        Path root = write("api/orders.yaml", withRef("./common.yaml#/components/schemas/Error"));

        assertThat(loader.load(root).violations()).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple(REF_POINTER,
                        "Referenced file 'common.yaml': YAML anchors are not allowed"));
    }

    @Test
    void resolvesNestedReferencesInsideTheDirectory() throws IOException {
        write("api/schemas/error.yaml", "Error: {type: object, properties: {code: {$ref: './code.yaml#/Code'}}}\n");
        write("api/schemas/code.yaml", "Code: {type: string}\n");
        write("api/schemas/common.yaml", """
                components:
                  schemas:
                    Error:
                      $ref: "./error.yaml#/Error"
                """);
        Path root = write("api/orders.yaml", withRef("schemas/common.yaml#/components/schemas/Error"));

        SpecLoadResult result = loader.load(root);

        assertThat(result.violations()).isEmpty();
        assertThat(result.spec().orElseThrow().openApi().getComponents().getSchemas()).containsKey("Error");
    }

    @Test
    void rejectsFileReferenceInContentWithoutFile() {
        SpecLoadResult result = loader.load(new SpecSource("openapi/orders.yaml",
                withRef("./common.yaml#/components/schemas/Error")));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple(REF_POINTER, "Reference './common.yaml#/components/schemas/Error' is not"
                        + " allowed: only local references (#/...) are allowed in content that does not come from"
                        + " a file"));
    }

    @Test
    void rejectsRemoteDiscriminatorMapping() {
        SpecLoadResult result = loader.load(new SpecSource("openapi/pets.yaml", """
                openapi: 3.0.3
                info: {title: Pets, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Pet:
                      type: object
                      discriminator:
                        propertyName: kind
                        mapping:
                          dog: "http://127.0.0.1:9/dog.yaml#/Dog"
                          cat: Cat
                    Cat: {type: object}
                """));

        assertThat(result.violations()).extracting(Violation::pointer)
                .containsExactly("/components/schemas/Pet/discriminator/mapping/dog");
    }

    @Test
    void allowsLocalReferencesInContentWithoutFile() {
        SpecLoadResult result = loader.load(new SpecSource("openapi/pets.yaml", """
                openapi: 3.0.3
                info: {title: Pets, version: 1.0.0}
                paths:
                  /pets:
                    get:
                      responses:
                        "200":
                          description: Pets.
                          content:
                            application/json:
                              schema: {$ref: "#/components/schemas/Pet"}
                components:
                  schemas:
                    Pet:
                      type: object
                      discriminator: {propertyName: kind, mapping: {cat: Cat, dog: "#/components/schemas/Pet"}}
                    Cat: {type: object}
                """));

        assertThat(result.violations()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            'x-base: &base {upstream: http://a:8080}'          | /x-base      | YAML anchors are not allowed
            'x-a: [1]\\nx-b: *base'                            | /x-b         | YAML aliases are not allowed
            'x-m:\\n  <<: {upstream: http://a:8080}'           | /x-m/<<      | YAML merge keys are not allowed
            'x-m: !!int "5"'                                  | /x-m         | YAML tags are not allowed
            'x-m:\\n  ? !!merge q\\n  : {a: 1}'                 | /x-m/q       | YAML tags are not allowed
            'x-a:\\n  - b\\n  - {c/d: &q 1}'                    | /x-a/1/c~1d  | YAML anchors are not allowed
            """)
    void rejectsYamlFeaturesThatTheParsersReadDifferently(String yaml, String pointer, String message) {
        String content = "openapi: 3.0.3\ninfo: {title: A, version: 1.0.0}\npaths: {}\n" + yaml.replace("\\n", "\n");

        SpecLoadResult result = loader.load(new SpecSource("a.yaml", content));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", pointer, message));
    }

    // SEC-67-4: the parsers read only the first document, so a reviewer could read an operation that does not run.
    // Content that starts with "{" is JSON, also in a .yaml file, because swagger-parser reads it as JSON.
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a.json | '{"paths": {"/admin": {"get": {}}}}\\n'  | line 2, column 1
            a.json | '  secret-text\\n'                       | line 2, column 3
            a.yaml | '---\\npaths:\\n  /admin:\\n    get: {}\\n' | line 2, column 1
            """)
    void rejectsContentAfterTheFirstJsonValue(String name, String trailing, String location) {
        String content = "{\"openapi\": \"3.0.3\", \"info\": {\"title\": \"A\", \"version\": \"1\"}, \"paths\": {}}\n"
                + trailing.replace("\\n", "\n");

        SpecLoadResult result = loader.load(new SpecSource(name, content));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "",
                        "Invalid JSON at " + location + ": content after the first JSON value is not allowed"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            '---\\npaths:\\n  /admin:\\n    get: {}\\n' | line 4, column 1
            '---\\n'                                   | line 4, column 1
            '...\\n---\\nx: 1\\n'                        | line 5, column 1
            """)
    void rejectsASecondYamlDocument(String trailing, String location) {
        String content = "openapi: 3.0.3\ninfo: {title: A, version: '1'}\npaths: {}\n" + trailing.replace("\\n", "\n");

        SpecLoadResult result = loader.load(new SpecSource("a.yaml", content));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "",
                        "Invalid YAML at " + location + ": only one YAML document is allowed"));
    }

    @Test
    void allowsOneYamlDocumentWithMarkers() {
        SpecLoadResult result = loader.load(new SpecSource("a.yaml",
                "---\nopenapi: 3.0.3\ninfo: {title: A, version: '1'}\npaths: {}\n...\n"));

        assertThat(result.violations()).isEmpty();
    }

    @Test
    void rejectsContentAfterTheFirstDocumentInReferencedFile() throws IOException {
        write("api/common.yaml", """
                components:
                  schemas:
                    Error: {type: object}
                ---
                paths: {}
                """);
        Path root = write("api/main.yaml", withRef("./common.yaml#/components/schemas/Error"));

        assertThat(loader.load(root).violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", REF_POINTER,
                        "Referenced file 'common.yaml': Invalid YAML at line 4, column 1: only one YAML document"
                                + " is allowed"));
    }

    // QA-69-2: in a file that does not end with .json, a reader sees YAML. Comments and one "..." line are allowed.
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a.yaml | ' # The end.\\n# More text.\\n'
            a.yml  | '\\n...\\n'
            a.yaml | '\\n...  \\n# After the end.\\n\\n'
            a.yaml | '\\r...\\r'
            """)
    void allowsCommentsAndOneDocumentEndAfterTheJsonValueInAYamlFile(String name, String trailing) {
        SpecLoadResult result = loader.load(new SpecSource(name, JSON_DOCUMENT + lineBreaks(trailing)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.spec()).isPresent();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a.json | '\\n# A comment.\\n'                 | line 2, column 1
            a.json | '\\n...\\n'                          | line 2, column 1
            a.yaml | '\\n...\\n...\\n'                    | line 3, column 1
            a.yaml | '\\n... # The end.\\n'               | line 2, column 1
            a.yaml | '\\n  ...\\nx: 1\\n'                 | line 3, column 1
            a.yaml | ' ...\\n'                            | line 1, column 75
            a.yaml | '\\r\\rsecret-text\\r'                 | line 3, column 1
            a.yaml | '\\r\\n\\r\\nsecret-text\\r\\n'         | line 3, column 1
            """)
    void rejectsOtherContentAfterTheJsonValue(String name, String trailing, String location) {
        SpecLoadResult result = loader.load(new SpecSource(name, JSON_DOCUMENT + lineBreaks(trailing)));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "",
                        "Invalid JSON at " + location + ": content after the first JSON value is not allowed"));
    }

    // QA-69-3: an editor can write a byte order mark. Jackson does not skip it in a String, swagger-parser does.
    @ParameterizedTest
    @CsvSource(textBlock = """
            a.json, '{"openapi": "3.0.3", "info": {"title": "A", "version": "1"}, "paths": {}}'
            a.yaml, '{"openapi": "3.0.3", "info": {"title": "A", "version": "1"}, "paths": {}}'
            a.yaml, 'openapi: 3.0.3\\ninfo: {title: A, version: "1"}\\npaths: {}'
            """)
    void loadsAFileWithAByteOrderMark(String name, String content) throws IOException {
        Path file = write("api/" + name, "\uFEFF" + content.replace("\\n", "\n") + "\n");

        SpecLoadResult result = loader.load(file);

        assertThat(result.violations()).isEmpty();
        assertThat(result.spec()).hasValueSatisfying(spec -> assertThat(spec.raw().path("openapi").asText())
                .isEqualTo("3.0.3"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a.json | '{"openapi": "3.0.3"}\\nx'              | Invalid JSON at line 2, column 1: content after the first JSON value is not allowed
            a.json | '{"openapi": "3.0.3",}'                 | Invalid JSON at line 1, column 21: Unexpected character ('}' (code 125)): was expecting double-quote to start field name
            a.yaml | 'openapi: 3.0.3\\n---\\nx: 1'            | Invalid YAML at line 2, column 1: only one YAML document is allowed
            a.yaml | 'openapi: 3.0.3\\nx: &a 1'               | YAML anchors are not allowed
            """)
    void reportsNoByteOrderMarkInAMessage(String name, String content, String message) throws IOException {
        Path file = write("api/" + name, "\uFEFF" + content.replace("\\n", "\n") + "\n");

        assertThat(loader.load(file).violations()).extracting(Violation::message)
                .containsExactly(message)
                .allSatisfy(text -> assertThat(text).doesNotContain("\uFEFF"));
    }

    // SEC-69-1: a pointer contains the keys of the path, so a long key made each pointer long. SnakeYAML already
    // limits an implicit YAML key to 1024 characters. An explicit key (?) and a JSON key had no limit.
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a.yaml | 'openapi: 3.0.3\\nx-a:\\n  - ? KEY\\n    : 1\\n'    | /x-a/0 | Invalid YAML at line 3, column 7
            a.yaml | 'openapi: 3.0.3\\n? KEY\\n: 1\\n'                   | ''     | Invalid YAML at line 2, column 3
            a.json | '{"openapi": "3.0.3", "x-a": [{"KEY": 1}]}'      | /x-a/0 | Invalid JSON at line 1, column 31
            a.json | '{"KEY": 1}'                                     | ''     | Invalid JSON at line 1, column 2
            """)
    void rejectsAKeyLongerThanTheLimit(String name, String content, String pointer, String location) {
        String key = "k".repeat(RawDocuments.MAX_KEY_LENGTH + 1);

        SpecLoadResult result = loader.load(new SpecSource(name, content.replace("\\n", "\n").replace("KEY", key)));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", pointer,
                        location + ": a key has 1025 characters. The maximum is 1024 characters"));
    }

    // The SEC-69-1 case: a key of 1,000,000 characters and 300,000 items below it took minutes before the fix.
    @Test
    void rejectsALongExplicitKeyAboveManyNodes() {
        String key = "k".repeat(1_000_000);
        String content = "openapi: 3.0.3\n? " + key + "\n: [" + "1,".repeat(300_000) + "1]\n";

        assertThat(loader.load(new SpecSource("a.yaml", content)).violations())
                .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "",
                        "Invalid YAML at line 2, column 3: a key has 1000000 characters. The maximum is 1024 characters"));
    }

    @Test
    void rejectsALongJsonKeyAboveManyNodes() {
        String key = "k".repeat(1_000_000);
        String content = "{\"openapi\": \"3.0.3\", \"" + key + "\": [" + "1,".repeat(300_000) + "1]}";

        assertThat(loader.load(new SpecSource("a.json", content)).violations())
                .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "",
                        "Invalid JSON at line 1, column 22: a key has 1000000 characters. The maximum is 1024 characters"));
    }

    @ParameterizedTest
    @CsvSource({"a.yaml, 'x-a: {KEY: 1}'", "a.json, '\"x-a\": {\"KEY\": 1}'"})
    void allowsAKeyAtTheLimit(String name, String member) {
        String key = "k".repeat(RawDocuments.MAX_KEY_LENGTH);
        String content = name.endsWith(".json")
                ? "{\"openapi\": \"3.0.3\", \"info\": {\"title\": \"A\", \"version\": \"1\"}, \"paths\": {}, "
                        + member.replace("KEY", key) + "}"
                : "openapi: 3.0.3\ninfo: {title: A, version: '1'}\npaths: {}\n" + member.replace("KEY", key) + "\n";

        assertThat(loader.load(new SpecSource(name, content)).violations()).isEmpty();
    }

    // QA-69-7: swagger-parser reads YAML with the code point limit of SnakeYAML (3 MB), not 5 MB.
    @Test
    void rejectsYamlLargerThanTheLimitOfTheParser() {
        String content = "openapi: 3.0.3\nx-big: '" + "a".repeat(4 * 1024 * 1024) + "'\n";

        assertThat(loader.load(new SpecSource("big.yaml", content)).violations())
                .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "", "The YAML file has " + content.length()
                        + " characters. The maximum is 3145728 characters (3 MB)"));
    }

    @Test
    void loadsJsonLargerThanTheYamlLimit() {
        String content = "{\"openapi\": \"3.0.3\", \"info\": {\"title\": \"A\", \"version\": \"1\"}, \"paths\": {},"
                + " \"x-big\": \"" + "a".repeat(4 * 1024 * 1024) + "\"}";

        assertThat(loader.load(new SpecSource("big.json", content)).violations()).isEmpty();
    }

    @Test
    void rejectsContentLargerThanTheLimit() {
        String content = "openapi: 3.0.3\nx-big: '" + "a".repeat((int) RawDocuments.MAX_BYTES) + "'\n";

        assertThat(loader.load(new SpecSource("big.yaml", content)).violations())
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("", "The file has " + content.length()
                        + " bytes. The maximum is 5242880 bytes (5 MB)"));
    }

    @Test
    void rejectsFileLargerThanTheLimitBeforeReadingIt() throws IOException {
        Path big = dir.resolve("big.json");
        try (var channel = Files.newByteChannel(big, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(RawDocuments.MAX_BYTES).write(java.nio.ByteBuffer.wrap(new byte[] {'x'}));
        }

        assertThat(loader.load(big).violations()).extracting(Violation::message)
                .containsExactly("The file has 5242881 bytes. The maximum is 5242880 bytes (5 MB)");
    }

    @Test
    void rejectsPathItemReferenceWithOtherFields() throws IOException {
        write("api/admin.yaml", """
                delete:
                  security: [{entra: []}]
                  responses: {"204": {description: Deleted.}}
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.0.3
                info: {title: Admin, version: 1.0.0}
                security: [{entra: []}]
                paths:
                  /admin:
                    $ref: './admin.yaml'
                    summary: Admin.
                    delete:
                      security: []
                      responses: {"204": {description: x}}
                """);

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "/paths/~1admin", notSupported("./admin.yaml")));
    }

    // SEC-67 DECISION: swagger-parser resolves this file reference, but every path item reference is rejected.
    @Test
    void rejectsPathItemReferenceWithoutOtherFields() throws IOException {
        write("api/admin.yaml", """
                delete:
                  responses: {"204": {description: Deleted.}}
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.0.3
                info: {title: Admin, version: 1.0.0}
                paths:
                  /admin:
                    $ref: './admin.yaml'
                """);

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "/paths/~1admin", notSupported("./admin.yaml")));
    }

    /**
     * SEC-67-1 with files: swagger-parser follows the {@code $ref} of {@code Admin} to {@code inner.yaml} and drops
     * the {@code get} next to it. The loader checks the referenced file too.
     */
    @Test
    void rejectsPathItemReferenceInReferencedFileThatDropsSiblingOperations() throws IOException {
        write("api/inner.yaml", """
                components:
                  pathItems:
                    Inner:
                      post:
                        responses: {"200": {description: OK}}
                """);
        write("api/admin.yaml", """
                components:
                  pathItems:
                    Admin:
                      $ref: './inner.yaml#/components/pathItems/Inner'
                      get:
                        security: [{entra: []}]
                        responses: {"200": {description: OK}}
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.1.0
                info: {title: Orders, version: 1.0.0}
                paths:
                  /orders/{id}:
                    get:
                      security: []
                      parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                      responses: {"200": {description: OK}}
                  /orders/admin:
                    $ref: './admin.yaml#/components/pathItems/Admin'
                """);

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("oas-parse", "/paths/~1orders~1admin",
                                notSupported("./admin.yaml#/components/pathItems/Admin")),
                        tuple("oas-parse", "/paths/~1orders~1admin/$ref",
                                "In referenced file 'admin.yaml' at '/components/pathItems/Admin': "
                                        + notSupported("./inner.yaml#/components/pathItems/Inner")));
    }

    // The root file has no path item reference. A callback reference reaches one in another file. SEC-69-2: the
    // callback reference to another file is also rejected.
    @Test
    void rejectsPathItemReferenceInCallbackOfReferencedFile() throws IOException {
        write("api/hooks.yaml", """
                components:
                  pathItems:
                    Event:
                      post:
                        responses: {"200": {description: OK}}
                  callbacks:
                    OnEvent:
                      '{$request.body#/url}':
                        $ref: '#/components/pathItems/Event'
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.1.0
                info: {title: Orders, version: 1.0.0}
                paths:
                  /subscriptions:
                    post:
                      responses: {"201": {description: Created}}
                      callbacks:
                        onEvent:
                          $ref: './hooks.yaml#/components/callbacks/OnEvent'
                """);

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("oas-parse", "/paths/~1subscriptions/post/callbacks/onEvent",
                                callbackNotSupported("./hooks.yaml#/components/callbacks/OnEvent")),
                        tuple("oas-parse", "/paths/~1subscriptions/post/callbacks/onEvent/$ref",
                                "In referenced file 'hooks.yaml' at '/components/callbacks/OnEvent/{$request.body#~1url}': "
                                        + notSupported("#/components/pathItems/Event")));
    }

    // SEC-69-2: a callback $ref to another file can point to any place of that file, and the check does not follow
    // it. Here the target is outside components/callbacks, so only the rejection of the reference finds it.
    @ParameterizedTest
    @CsvSource({"./hooks.yaml#/x-hooks/OnEvent", "hooks.yaml", "hooks.yaml#/x-hooks/OnEvent"})
    void rejectsCallbackReferenceToAnotherFile(String reference) throws IOException {
        write("api/hooks.yaml", """
                x-hooks:
                  OnEvent:
                    '{$request.body#/url}':
                      $ref: '#/x-admin'
                x-admin:
                  get:
                    responses: {"200": {description: OK}}
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.1.0
                info: {title: Orders, version: 1.0.0}
                paths:
                  /subscriptions:
                    post:
                      responses: {"201": {description: Created}}
                      callbacks:
                        onEvent:
                          $ref: '%s'
                """.formatted(reference));

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "/paths/~1subscriptions/post/callbacks/onEvent",
                        callbackNotSupported(reference)));
    }

    @Test
    void rejectsCallbackReferenceToAnotherFileInReferencedFile() throws IOException {
        write("api/other.yaml", "x-hooks: {}\n");
        write("api/hooks.yaml", """
                components:
                  callbacks:
                    OnEvent:
                      $ref: './other.yaml#/x-hooks/OnEvent'
                """);
        Path root = write("api/main.yaml", """
                openapi: 3.1.0
                info: {title: Orders, version: 1.0.0}
                paths:
                  /subscriptions:
                    post:
                      responses: {"201": {description: Created}}
                      callbacks:
                        onEvent:
                          $ref: '#/components/callbacks/OnEvent'
                components:
                  callbacks:
                    OnEvent:
                      '{$request.body#/url}':
                        post:
                          responses: {"200": {description: OK}}
                  schemas:
                    Hooks:
                      $ref: './hooks.yaml#/components/callbacks'
                """);

        SpecLoadResult result = loader.load(root);

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("oas-parse", "/components/schemas/Hooks/$ref",
                        "In referenced file 'hooks.yaml' at '/components/callbacks/OnEvent': "
                                + callbackNotSupported("./other.yaml#/x-hooks/OnEvent")));
    }

    private static String callbackNotSupported(String reference) {
        return "Callback reference '" + reference + "' to another file is not supported. Define the callback in this"
                + " file.";
    }

    private static String notSupported(String reference) {
        return "Path item reference '" + reference + "' is not supported. Inline the operations.";
    }

    /** The CSV values write a line feed as {@code \\n} and a carriage return as {@code \\r}. */
    private static String lineBreaks(String text) {
        return text.replace("\\n", "\n").replace("\\r", "\r");
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static String withRef(String ref) {
        return """
                openapi: 3.0.3
                info: {title: Orders, version: 1.0.0}
                paths:
                  /orders:
                    get:
                      responses:
                        "500":
                          description: Error.
                          content:
                            application/json:
                              schema:
                                $ref: '%s'
                """.formatted(ref);
    }
}
