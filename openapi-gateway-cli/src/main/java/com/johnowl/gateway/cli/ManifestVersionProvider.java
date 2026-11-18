package com.johnowl.gateway.cli;

import picocli.CommandLine.IVersionProvider;

/** Reads the version from {@code Implementation-Version} in the jar manifest. */
final class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {"oas-gateway-validate " + version()};
    }

    /** The project version from the jar manifest, or {@code development} outside the jar. */
    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }
}
