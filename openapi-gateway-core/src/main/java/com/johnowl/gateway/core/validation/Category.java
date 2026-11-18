package com.johnowl.gateway.core.validation;

/** The kind of problem that a rule checks. {@link #CONTRACT} rules protect the gateway. */
public enum Category {
    CONTRACT,
    NAMING,
    DOCUMENTATION,
    ERRORS,
    SECURITY,
    DESIGN
}
