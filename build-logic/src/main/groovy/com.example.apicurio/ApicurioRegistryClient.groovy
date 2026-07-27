package com.example.apicurio

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal Apicurio Registry Core API v3 client for CI governance tasks.
 *
 * Register always uses POST /groups/{g}/artifacts?ifExists=FIND_OR_CREATE_VERSION&canonical=true
 * (idempotent for existing artifacts). Do NOT use the versions endpoint for register —
 * FIND_OR_CREATE_VERSION on /versions is not content-idempotent.
 *
 * Compat / incompatible-demo use /versions?dryRun=true (CREATE_VERSION semantics) so a
 * compatibility rejection is adjudicated without persisting (avoids #6670 with
 * FIND_OR_CREATE_VERSION+dryRun on createArtifact).
 */
@CompileStatic
class ApicurioRegistryClient {

    private final String baseUrl
    private final HttpClient http
    private final JsonSlurper slurper = new JsonSlurper()

    ApicurioRegistryClient(String registryUrl) {
        String trimmed = registryUrl.endsWith('/') ? registryUrl[0..-2] : registryUrl
        this.baseUrl = trimmed + '/apis/registry/v3'
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
    }

    void register(ApicurioArtifact artifact) {
        String content = artifact.file.getText(StandardCharsets.UTF_8.name())
        createArtifact(artifact.groupId, artifact.artifactId, artifact.artifactType, content,
                false, 'FIND_OR_CREATE_VERSION', true)
    }

    /**
     * Dry-run compatibility check that must not persist.
     * Existing artifacts: POST /versions?dryRun=true&canonical=true.
     * New artifacts: POST /artifacts?dryRun=true&ifExists=FAIL.
     */
    void compatCheck(ApicurioArtifact artifact) {
        String content = artifact.file.getText(StandardCharsets.UTF_8.name())
        if (artifactExists(artifact.groupId, artifact.artifactId)) {
            createVersionDryRun(artifact.groupId, artifact.artifactId, content, true)
        } else {
            createArtifact(artifact.groupId, artifact.artifactId, artifact.artifactType, content,
                    true, 'FAIL', true)
        }
    }

    boolean artifactExists(String groupId, String artifactId) {
        def uri = URI.create("${baseUrl}/groups/${enc(groupId)}/artifacts/${enc(artifactId)}")
        def req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
        def resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) {
            return true
        }
        if (resp.statusCode() == 404) {
            return false
        }
        throw new IllegalStateException(
                "Unexpected status ${resp.statusCode()} probing ${groupId}/${artifactId}: ${resp.body()}")
    }

    private void createArtifact(String groupId, String artifactId, String artifactType, String content,
                                boolean dryRun, String ifExists, boolean canonical) {
        def query = "ifExists=${ifExists}&canonical=${canonical}&dryRun=${dryRun}"
        def uri = URI.create("${baseUrl}/groups/${enc(groupId)}/artifacts?${query}")
        def body = JsonOutput.toJson([
                artifactId  : artifactId,
                artifactType: artifactType,
                firstVersion: [
                        content: [
                                content    : content,
                                contentType: contentTypeFor(artifactType)
                        ]
                ]
        ])
        postJson(uri, body, "create artifact ${groupId}/${artifactId} (dryRun=${dryRun})")
    }

    private void createVersionDryRun(String groupId, String artifactId, String content, boolean canonical) {
        def query = "canonical=${canonical}&dryRun=true"
        def uri = URI.create(
                "${baseUrl}/groups/${enc(groupId)}/artifacts/${enc(artifactId)}/versions?${query}")
        def body = JsonOutput.toJson([
                content: [
                        content    : content,
                        contentType: 'application/json'
                ]
        ])
        postJson(uri, body, "create version ${groupId}/${artifactId} (dryRun=true)")
    }

    private void postJson(URI uri, String body, String action) {
        def req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        def resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        int code = resp.statusCode()
        if (code >= 200 && code < 300) {
            return
        }
        String detail = resp.body()
        try {
            def parsed = slurper.parseText(detail)
            if (parsed instanceof Map) {
                def map = (Map) parsed
                if (map.title != null) {
                    detail = map.title as String
                } else if (map.message != null) {
                    detail = map.message as String
                }
            }
        } catch (ignored) {
            // keep raw body
        }
        throw new IllegalStateException("Apicurio ${action} failed HTTP ${code}: ${detail}")
    }

    private static String contentTypeFor(String artifactType) {
        return 'JSON'.equalsIgnoreCase(artifactType) ? 'application/json' : 'application/json'
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
