package com.johnowl.gateway.core.validation;

/** A rule that the engine calls once for each operation of each OAS file. */
public non-sealed interface OperationRule extends Rule {

    void check(OperationContext context, Reporter reporter);
}
