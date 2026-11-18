package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaTypesTest {

    @Test
    void essenceIgnoresParametersAndCase() {
        assertThat(MediaTypes.essence(" Application/Problem+JSON ; charset=utf-8"))
                .isEqualTo("application/problem+json");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "application/json",
        "application/problem+json",
        "application/vnd.orders+json",
        "application/json; charset=utf-8",
        "Application/JSON",
        "APPLICATION/PROBLEM+JSON;charset=UTF-8"
    })
    void jsonMediaTypes(String mediaType) {
        assertThat(MediaTypes.isJson(mediaType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "text/plain",
        "application/octet-stream",
        "application/xml",
        "application/x-ndjson",
        "application/jsonl",
        "text/plain; format=json",
        "*/*"
    })
    void otherMediaTypes(String mediaType) {
        assertThat(MediaTypes.isJson(mediaType)).isFalse();
    }
}
