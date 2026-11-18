package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The split helpers of the path rules (specs 029 and 030). */
class PathSegmentsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/orders/{id}/items | orders,items",
        "/orders/{id}:cancel/items | orders,items",
        "/orders/ | orders",
        "/v1/users | v1,users",
        "/ | ''",
        "/{id} | ''"
    })
    void literalSegmentsSkipVariablesAndEmptySegments(String path, String expected) {
        assertThat(PathSegments.literal(path)).isEqualTo(split(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "get-orders | get,orders",
        "order_items | order,items",
        "createOrder | create,Order",
        "orders.fetch | orders,fetch",
        "getHTTPStatus | get,HTTPStatus",
        "createOrder_v2.json | create,Order,v2,json",
        "settings | settings",
        "status--update | status,update",
        "orders:cancel | orders,cancel",
        "orders~list | orders,list",
        "-get- | get",
        "v1 | v1",
        "ORDERS | ORDERS"
    })
    void wordsSplitOnSeparatorsAndOnLowerToUpperCase(String segment, String expected) {
        assertThat(PathSegments.words(segment)).isEqualTo(split(expected));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/orders/{id}:delete | orders,delete",
        "/orders/getAll.{format} | orders,get,All",
        "/orders/{id}Delete | orders,Delete",
        "/get{id}All | get,All",
        "/targets/{getTarget} | targets",
        "/{id} | ''"
    })
    void wordsOutsideVariablesRemoveVariablesInsideASegment(String path, String expected) {
        assertThat(PathSegments.wordsOutsideVariables(path)).isEqualTo(split(expected));
    }

    private static List<String> split(String expected) {
        return expected.isEmpty() ? List.of() : List.of(expected.split(","));
    }
}
