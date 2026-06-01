package com.example.messaging.core.registry;

import com.example.messaging.core.exception.RegistryUnavailableException;
import com.example.messaging.core.exception.SchemaNotFoundException;
import com.example.messaging.core.model.ResolvedSchema;
import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import com.microsoft.kiota.ApiException;
import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.models.VersionMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Thin façade over the Apicurio Java SDK (spec §10.1).
 *
 * <p>Maps 404 → {@link SchemaNotFoundException}, connectivity errors → {@link RegistryUnavailableException}.
 *
 * <p><b>Note on {@code fetchByGlobalId}:</b> the Apicurio 3.x SDK's {@code /ids/globalIds/{id}}
 * endpoint returns raw schema content only (no metadata). The caller must supply {@code schemaType}
 * from message headers — this is always available on the consumer path (spec §6 {@code X-Schema-Type}).
 *
 * <p>Auth: configured via {@link RegistryClientOptions} (anonymous by default; use
 * {@code options.oauth2(...)} for OIDC client-credentials — spec §16).
 */
public class ApicurioClient {

    private static final Logger log = LoggerFactory.getLogger(ApicurioClient.class);

    private final RegistryClient registryClient;

    public ApicurioClient(RegistryClientOptions options) {
        this.registryClient = RegistryClientFactory.create(options);
    }

    /** Convenience constructor for anonymous (no-auth) access. */
    public ApicurioClient(String registryUrl) {
        this(RegistryClientOptions.create(registryUrl));
    }

    /** Package-private constructor for unit tests — inject a mock/spy directly. */
    ApicurioClient(RegistryClient registryClient) {
        this.registryClient = registryClient;
    }

    // ---- public API -------------------------------------------------------

    /**
     * Fetch raw schema content by Apicurio global ID (fastest consumer path).
     *
     * @param globalId  the Apicurio global ID from the {@code X-Schema-GlobalId} header
     * @param schemaType the type resolved from the {@code X-Schema-Type} header (required
     *                   because the globalIds endpoint returns content only, not metadata)
     */
    public ResolvedSchema fetchByGlobalId(long globalId, SchemaType schemaType) {
        String ctx = "globalId=" + globalId;
        try {
            InputStream content = registryClient.ids().globalIds().byGlobalId(globalId).get();
            byte[] bytes = content.readAllBytes();
            return new ResolvedSchema(globalId, schemaType, bytes);
        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 404) throw new SchemaNotFoundException(ctx, e);
            throw new RegistryUnavailableException(ctx, e);
        } catch (SchemaNotFoundException | RegistryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RegistryUnavailableException(ctx, e);
        }
    }

    /**
     * Fetch schema content + metadata by group / artifact / version coordinates.
     * Two HTTP calls: metadata first (to get globalId + type), then content.
     */
    public ResolvedSchema fetchByCoordinates(SchemaCoordinates coords) {
        String ctx = coords.toString();
        try {
            VersionMetaData meta = registryClient
                    .groups().byGroupId(coords.groupId())
                    .artifacts().byArtifactId(coords.artifactId())
                    .versions().byVersionExpression(coords.versionExpression())
                    .get();
            InputStream content = registryClient
                    .groups().byGroupId(coords.groupId())
                    .artifacts().byArtifactId(coords.artifactId())
                    .versions().byVersionExpression(coords.versionExpression())
                    .content().get();
            SchemaType type = SchemaType.fromArtifactType(meta.getArtifactType());
            byte[] bytes = content.readAllBytes();
            return new ResolvedSchema(meta.getGlobalId(), type, bytes);
        } catch (ApiException e) {
            if (e.getResponseStatusCode() == 404) throw new SchemaNotFoundException(ctx, e);
            throw new RegistryUnavailableException(ctx, e);
        } catch (SchemaNotFoundException | RegistryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new RegistryUnavailableException(ctx, e);
        }
    }

    /**
     * Fetch the latest version of an artifact (delegates to {@link #fetchByCoordinates}).
     */
    public ResolvedSchema latestVersion(String groupId, String artifactId) {
        return fetchByCoordinates(SchemaCoordinates.latest(groupId, artifactId));
    }
}
