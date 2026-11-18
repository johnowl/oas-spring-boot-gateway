package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.johnowl.gateway.core.spec.SpecSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleExampleDocumentsTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Test
    void addsOnlyWhatTheExcerptDoesNotHave() throws Exception {
        List<SpecSource> sources = RuleExampleDocuments.wrap("""
                x-gateway:
                  upstream: http://orders:8080/v1
                paths:
                  /orders/{id}:
                    get:
                      security: []
                    post:
                      responses:
                        "201":
                          description: Created.
                """);

        assertThat(sources).extracting(SpecSource::path).containsExactly("example.yaml");
        JsonNode document = YAML.readTree(sources.getFirst().content());
        assertThat(document.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(document.path("x-gateway").path("upstream").asText()).isEqualTo("http://orders:8080/v1");
        assertThat(document.has("security")).isFalse();
        assertThat(document.at("/components/securitySchemes/entra/type").asText()).isEqualTo("openIdConnect");
        JsonNode get = document.at("/paths/~1orders~1{id}/get");
        assertThat(get.path("security").isEmpty()).isTrue();
        assertThat(get.at("/responses/200/description").asText()).isEqualTo("OK.");
        assertThat(get.at("/parameters/0/name").asText()).isEqualTo("id");
        JsonNode post = document.at("/paths/~1orders~1{id}/post");
        assertThat(post.path("responses").size()).isEqualTo(1);
        assertThat(post.path("responses").has("201")).isTrue();
    }

    @Test
    void keepsACompleteDocument() throws Exception {
        String swagger = "swagger: \"2.0\"\npaths: {}\n";

        assertThat(RuleExampleDocuments.wrap(swagger)).containsExactly(new SpecSource("example.yaml", swagger));
    }

    @Test
    void namesEachDocumentAfterItsComment() throws Exception {
        List<SpecSource> sources = RuleExampleDocuments.wrap(
                "# team-a/orders.yaml\npaths: {}\n---\n# team-b/orders.yaml: the same name.\npaths: {}\n");

        assertThat(sources).extracting(SpecSource::path).containsExactly("team-a/orders.yaml", "team-b/orders.yaml");
    }

    @Test
    void rejectsTwoDocumentsForTheSameFile() {
        assertThatThrownBy(() -> RuleExampleDocuments.wrap("# a.yaml\npaths: {}\n---\n# a.yaml\npaths: {}\n"))
                .hasMessage("has two documents for the file a.yaml");
    }
}
