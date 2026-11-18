package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.access.AccessRuleCompiler;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Reads the permission lists of {@code x-gateway-authorization} from the parsed model. */
final class Permissions {

    private Permissions() {
    }

    /** Calls the consumer with the relative pointer (for example {@code all-of/0}) and the permission. */
    static void each(Operation operation, BiConsumer<String, String> consumer) {
        Map<String, Object> extensions = operation.getExtensions();
        if (extensions == null || !(extensions.get(AccessRuleCompiler.EXTENSION) instanceof Map<?, ?> authorization)) {
            return;
        }
        for (String key : List.of("all-of", "any-of")) {
            if (authorization.get(key) instanceof List<?> permissions) {
                for (int i = 0; i < permissions.size(); i++) {
                    if (permissions.get(i) instanceof String permission) {
                        consumer.accept(key + "/" + i, permission);
                    }
                }
            }
        }
    }
}
