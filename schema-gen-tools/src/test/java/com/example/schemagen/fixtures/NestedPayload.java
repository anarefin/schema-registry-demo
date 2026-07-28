package com.example.schemagen.fixtures;

/** Nested value object used to exercise object nodes under {@code properties}. */
public final class NestedPayload {

    private final String detail;

    public NestedPayload(String detail) {
        this.detail = detail;
    }

    public String getDetail() {
        return detail;
    }
}
