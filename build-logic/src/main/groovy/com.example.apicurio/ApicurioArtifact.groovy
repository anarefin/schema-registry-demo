package com.example.apicurio

import groovy.transform.CompileStatic
import groovy.transform.Immutable

@Immutable
@CompileStatic
class ApicurioArtifact {
    String groupId
    String artifactId
    File file
    String artifactType = 'JSON'
}
