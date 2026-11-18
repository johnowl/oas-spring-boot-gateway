package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Each JSON media type with a {@code schema}, in the request body and in the responses of the operation, has an
 * example: {@code example} or {@code examples} on the media type, or at the top level of its schema after
 * {@code $ref} resolution. In OAS 3.1, an example next to a schema {@code $ref} also counts. Examples on properties
 * do not count. A media type whose schema {@code $ref} cannot be resolved is skipped.
 */
public final class SchemaExamplesPresentRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "schema-examples-present", Category.DOCUMENTATION, Severity.INFO,
            "Each JSON media type (application/json or +json) with a schema, in the request body and in the"
                    + " responses, has example or examples on the media type or at the top level of its schema.",
            "Examples help client developers more than a schema alone. Documentation tools show them next to the"
                    + " operation.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        OpenAPI openApi = context.spec().spec().openApi();
        if (context.operation().getRequestBody() != null) {
            RequestBody body = ComponentReferences.requestBody(openApi, context.operation().getRequestBody());
            if (body != null) {
                check(openApi, body.getContent(), context.pointer() + "/requestBody", "the request body", reporter);
            }
        }
        if (context.operation().getResponses() == null) {
            return;
        }
        context.operation().getResponses().forEach((code, response) -> {
            ApiResponse resolved = ComponentReferences.response(openApi, response);
            if (resolved != null) {
                check(openApi, resolved.getContent(), context.pointer() + "/responses/" + JsonPointers.escape(code),
                        "response '" + PrintableText.printable(code) + "'", reporter);
            }
        });
    }

    private static void check(OpenAPI openApi, Content content, String pointer, String owner, Reporter reporter) {
        if (content == null) {
            return;
        }
        content.forEach((name, mediaType) -> {
            if (mediaType == null || mediaType.getSchema() == null || !MediaTypes.isJson(name)
                    || passes(openApi, mediaType)) {
                return;
            }
            reporter.report(pointer + "/content/" + JsonPointers.escape(name), "Media type '"
                    + PrintableText.printable(name) + "' of " + owner + " has no example. Add example or examples"
                    + " to the media type or to its schema.");
        });
    }

    /**
     * The media type has an example, or its schema has a {@code $ref} that the rule cannot resolve, for example to
     * {@code $defs} inside a schema. The rule does not guess, so it skips such a media type. A reference cycle
     * fails.
     */
    private static boolean passes(OpenAPI openApi, MediaType mediaType) {
        if (present(mediaType.getExample()) || present(mediaType.getExamples())) {
            return true;
        }
        // OAS 3.0 ignores the siblings of a $ref. In OAS 3.1, the Schema Object applies them.
        boolean siblingsCount = openApi.getSpecVersion() == SpecVersion.V31;
        Set<String> seen = new HashSet<>();
        Schema<?> schema = mediaType.getSchema();
        while (schema != null) {
            boolean reference = schema.get$ref() != null;
            if ((!reference || siblingsCount) && (present(schema.getExample()) || present(schema.getExamples()))) {
                return true;
            }
            if (!reference || !seen.add(schema.get$ref())) {
                return false;
            }
            schema = ComponentReferences.schema(openApi, schema);
        }
        // The last $ref cannot be resolved.
        return true;
    }

    /** A value that is not {@code null}, and not an empty string, list, or object. */
    static boolean present(Object value) {
        return switch (value) {
            case null -> false;
            case JsonNode node -> !node.isNull() && !node.isMissingNode()
                    && !(node.isContainerNode() && node.isEmpty()) && !(node.isTextual() && node.asText().isEmpty());
            case CharSequence text -> !text.isEmpty();
            case Collection<?> list -> !list.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            default -> true;
        };
    }
}
