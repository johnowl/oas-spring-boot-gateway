package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.config.OptionType;
import com.johnowl.gateway.core.config.RuleOptions;
import com.johnowl.gateway.core.config.RuleWithOptions;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Each media type of an error response ({@code 400} to {@code 599}, {@code 4XX}, {@code 5XX}, {@code default}) is
 * in the option {@code media-types}, by default {@code application/problem+json} (RFC 9457).
 */
public final class ErrorResponseProblemJsonRule implements OperationRule, RuleWithOptions {

    static final String MEDIA_TYPES = "media-types";

    static final List<String> DEFAULT_MEDIA_TYPES = List.of("application/problem+json");

    static final Pattern ERROR = Pattern.compile("^([45][0-9][0-9]|4XX|5XX|default)$");

    private static final RuleMeta META = BestPracticeRule.meta(
            "error-response-problem-json", Category.ERRORS, Severity.WARN,
            "Each media type of an error response (400 to 599, 4XX, 5XX, default) is in the option media-types"
                    + " (default: application/problem+json). Parameters such as charset are ignored.",
            "One error format lets clients handle the errors of all APIs with the same code (RFC 9457).");

    private final List<String> mediaTypes;
    private final Set<String> accepted;

    public ErrorResponseProblemJsonRule() {
        this(DEFAULT_MEDIA_TYPES);
    }

    private ErrorResponseProblemJsonRule(List<String> mediaTypes) {
        this.mediaTypes = List.copyOf(mediaTypes);
        this.accepted = mediaTypes.stream().map(MediaTypes::essence).collect(Collectors.toSet());
    }

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public Map<String, OptionType> optionTypes() {
        return Map.of(MEDIA_TYPES, OptionType.STRING_LIST);
    }

    @Override
    public Map<String, Object> optionDefaults() {
        return Map.of(MEDIA_TYPES, DEFAULT_MEDIA_TYPES);
    }

    @Override
    public Rule withOptions(RuleOptions options) {
        return new ErrorResponseProblemJsonRule(options.stringList(MEDIA_TYPES).orElse(DEFAULT_MEDIA_TYPES));
    }

    /** The accepted media types, as the configuration gives them. */
    List<String> mediaTypes() {
        return mediaTypes;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        if (context.operation().getResponses() == null) {
            return;
        }
        context.operation().getResponses().forEach((code, response) -> {
            if (!ERROR.matcher(code).matches()) {
                return;
            }
            ApiResponse resolved = ComponentReferences.response(context.spec().spec().openApi(), response);
            if (resolved == null || resolved.getContent() == null) {
                return;
            }
            for (String mediaType : resolved.getContent().keySet()) {
                if (!accepted.contains(MediaTypes.essence(mediaType))) {
                    reporter.report(context.pointer() + "/responses/" + JsonPointers.escape(code) + "/content/"
                                    + JsonPointers.escape(mediaType),
                            "Error response '" + PrintableText.printable(code) + "' has media type '"
                                    + PrintableText.printable(mediaType) + "'. Use "
                                    + String.join(" or ", mediaTypes) + ".");
                }
            }
        });
    }
}
