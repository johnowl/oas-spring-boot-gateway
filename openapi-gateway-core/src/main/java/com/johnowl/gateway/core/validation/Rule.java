package com.johnowl.gateway.core.validation;

/**
 * A validation rule. A rule only reports problems; the engine sets the rule id, the file and
 * the severity of each violation.
 */
public sealed interface Rule permits SpecRule, OperationRule, CrossSpecRule {

    RuleMeta meta();
}
