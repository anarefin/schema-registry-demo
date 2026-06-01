package com.example.messaging.core.converter;

import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;
import org.springframework.amqp.core.MessageProperties;

/**
 * Constants and codec for all {@code X-Schema-*} headers (spec §6).
 */
public final class SchemaMessageHeaders {

    // Schema identity headers
    public static final String GLOBAL_ID      = "X-Schema-GlobalId";
    public static final String GROUP_ID       = "X-Schema-GroupId";
    public static final String ARTIFACT_ID    = "X-Schema-ArtifactId";
    public static final String VERSION        = "X-Schema-Version";
    public static final String TYPE           = "X-Schema-Type";

    // Message tracing headers
    public static final String MESSAGE_ID     = "X-Message-Id";
    public static final String CORRELATION_ID = "X-Correlation-Id";

    // DLQ failure headers (spec §11, used by EventConsumerSupport)
    public static final String FAILURE_REASON         = "X-Failure-Reason";
    public static final String FAILURE_MESSAGE        = "X-Failure-Message";
    public static final String FAILURE_STACK_TRACE    = "X-Failure-StackTrace";
    public static final String FAILURE_ROUTING_KEY    = "X-Failure-Original-Routing-Key";
    public static final String FAILURE_FAILED_AT      = "X-Failure-Failed-At";
    public static final String FAILURE_RETRY_COUNT    = "X-Failure-Retry-Count";

    // Retry header
    public static final String RETRY_COUNT = "X-Retry-Count";

    private SchemaMessageHeaders() {}

    // ---- write helpers ----------------------------------------------------

    public static void setSchemaHeaders(
            MessageProperties props,
            long globalId,
            SchemaCoordinates coords,
            SchemaType schemaType) {
        props.setHeader(GLOBAL_ID, globalId);
        props.setHeader(GROUP_ID, coords.groupId());
        props.setHeader(ARTIFACT_ID, coords.artifactId());
        props.setHeader(VERSION, coords.versionExpression());
        props.setHeader(TYPE, schemaType.name());
        props.setContentType(schemaType.contentType());
    }

    // ---- read helpers ----------------------------------------------------

    public static Long getGlobalId(MessageProperties props) {
        Object v = props.getHeader(GLOBAL_ID);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    public static String getGroupId(MessageProperties props) {
        return headerString(props, GROUP_ID);
    }

    public static String getArtifactId(MessageProperties props) {
        return headerString(props, ARTIFACT_ID);
    }

    public static String getVersion(MessageProperties props) {
        return headerString(props, VERSION);
    }

    public static SchemaType getSchemaType(MessageProperties props) {
        String v = headerString(props, TYPE);
        if (v == null) return null;
        try { return SchemaType.valueOf(v); } catch (IllegalArgumentException e) { return null; }
    }

    public static int getRetryCount(MessageProperties props) {
        Object v = props.getHeader(RETRY_COUNT);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static String headerString(MessageProperties props, String key) {
        Object v = props.getHeader(key);
        return v != null ? v.toString() : null;
    }
}
