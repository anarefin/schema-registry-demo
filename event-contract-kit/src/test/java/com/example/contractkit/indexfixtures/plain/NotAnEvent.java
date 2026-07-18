package com.example.contractkit.indexfixtures.plain;

/** Fixture type deliberately WITHOUT {@code @EventMapping} — for the annotation/index mismatch path. */
public record NotAnEvent(String id) {}
