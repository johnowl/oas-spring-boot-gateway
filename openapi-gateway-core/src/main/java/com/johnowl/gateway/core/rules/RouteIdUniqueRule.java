package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.route.GatewayRouteBuilder;
import com.johnowl.gateway.core.route.XGatewaySettings;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.CrossSpecRule;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** No two routes of all files have the same id. */
public final class RouteIdUniqueRule implements CrossSpecRule {

    private static final RuleMeta META = new RuleMeta(
            "route-id-unique",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "No two routes of all files have the same id. The id contains the file name without the"
                    + " directory, so two files with the same name and the same path collide.",
            "The gateway registers routes by id. A second route with the same id replaces the first.");

    private final GatewayRouteBuilder builder = new GatewayRouteBuilder();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(List<SpecContext> specs, Reporter.ForFile reporter) {
        Map<String, List<Owner>> byId = new LinkedHashMap<>();
        for (SpecContext spec : specs) {
            XGatewaySettings xGateway = XGatewaySettings.of(spec.spec()).orElse(null);
            if (xGateway == null) {
                continue;
            }
            int prefixLength = xGateway.prefix() == null ? 0 : xGateway.prefix().length();
            for (GatewayRoute route : builder.build(spec.spec())) {
                String oasPath = route.path().length() == prefixLength ? "/" : route.path().substring(prefixLength);
                byId.computeIfAbsent(route.id(), id -> new ArrayList<>())
                        .add(new Owner(spec.file(), "/paths/" + JsonPointers.escape(oasPath)));
            }
        }
        byId.forEach((id, owners) -> {
            if (owners.size() < 2) {
                return;
            }
            for (Owner owner : owners) {
                Owner other = owners.stream().filter(o -> o != owner).findFirst().orElseThrow();
                reporter.report(owner.file(), owner.pointer(),
                        "Route id '" + id + "' is also used by " + other.file() + ". Rename one of the files.");
            }
        });
    }

    private record Owner(String file, String pointer) {
    }
}
