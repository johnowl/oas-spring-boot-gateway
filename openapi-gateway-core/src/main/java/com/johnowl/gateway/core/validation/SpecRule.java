package com.johnowl.gateway.core.validation;

/** A rule that the engine calls once for each OAS file. */
public non-sealed interface SpecRule extends Rule {

    void check(SpecContext context, Reporter reporter);
}
