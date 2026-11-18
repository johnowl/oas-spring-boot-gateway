package com.johnowl.gateway.core.validation;

/**
 * A suppression that covered no violation in a run.
 *
 * @param file the OAS file of the suppression
 * @param pointer the JSON pointer of the suppression entry
 * @param ruleId the rule that the entry suppresses
 */
public record UnusedSuppression(String file, String pointer, String ruleId) {
}
