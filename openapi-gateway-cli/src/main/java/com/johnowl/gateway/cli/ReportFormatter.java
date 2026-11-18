package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.validation.ValidationReport;
import java.io.PrintWriter;

/** Writes a validation report in one output format. */
interface ReportFormatter {

    /**
     * @param fileCount the number of OAS files that the CLI checked
     */
    void write(ValidationReport report, int fileCount, PrintWriter out);
}
