package com.example.schemagen.mappingfixtures;

/** Unsupported shape: a non-public (package-private) class. */
final class PackagePrivateEvent {

    private final String id;

    PackagePrivateEvent(String id) {
        this.id = id;
    }

    String getId() {
        return id;
    }
}
