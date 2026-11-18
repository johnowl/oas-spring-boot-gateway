package com.johnowl.gateway.core.config;

import com.johnowl.gateway.core.validation.Rule;
import java.util.Map;

/** A rule that accepts options in the configuration file. Other rules accept no options. */
public interface RuleWithOptions {

    /** The option names that the rule accepts, and their types. */
    Map<String, OptionType> optionTypes();

    /**
     * The default value of each option of {@link #optionTypes()}, with the same keys. A {@link OptionType#STRING_LIST}
     * option has a {@code List<String>}. The CLI subcommands {@code rules} and {@code explain} show these values.
     */
    Map<String, Object> optionDefaults();

    /**
     * A rule with the same id that uses these options. An option that the file does not set keeps its default.
     *
     * @param options validated against {@link #optionTypes()}
     */
    Rule withOptions(RuleOptions options);
}
