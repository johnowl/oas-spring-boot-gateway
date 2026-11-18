package com.johnowl.gateway.cli;

/**
 * A position in a source file. Lines and columns start at 1.
 */
record SourcePosition(int line, int column) {
}
