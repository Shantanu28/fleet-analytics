package com.fleet.analytics.web.dashboard;

import com.fleet.analytics.metrics.model.DisplayValue;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The contract's {@code Display}: a presentation-precision value and its unit.
 *
 * <p>The value stays a string on the wire because its scale is part of the answer — {@code "23.00"}
 * and {@code "23"} are different presentations of the same money, and a JSON number would lose that
 * the moment it was parsed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisplayResponse(String value, String unit) {

    /** @return null when the metric has no display, so the field is omitted rather than nulled. */
    public static DisplayResponse from(DisplayValue display) {
        return display == null
                ? null
                : new DisplayResponse(display.value(), display.unit().wireName());
    }
}
