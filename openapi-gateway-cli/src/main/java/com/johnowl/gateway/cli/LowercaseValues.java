package com.johnowl.gateway.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Lists enum values in lowercase in the help, as users type them. */
class LowercaseValues extends ArrayList<String> {

    LowercaseValues(Enum<?>[] values) {
        super(Arrays.stream(values).map(value -> value.name().toLowerCase(Locale.ROOT)).toList());
    }

    static final class Formats extends LowercaseValues {
        Formats() {
            super(OutputFormat.values());
        }
    }

    static final class RulesFormats extends LowercaseValues {
        RulesFormats() {
            super(RulesFormat.values());
        }
    }

    static final class DiffFormats extends LowercaseValues {
        DiffFormats() {
            super(DiffFormat.values());
        }
    }

    static final class FailOns extends LowercaseValues {
        FailOns() {
            super(FailOn.values());
        }
    }
}
