package com.johnowl.gateway.core.validation;

import java.util.List;

/** A rule that the engine calls once for each run, with all OAS files that parsed. */
public non-sealed interface CrossSpecRule extends Rule {

    void check(List<SpecContext> specs, Reporter.ForFile reporter);
}
