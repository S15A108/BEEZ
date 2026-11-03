package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.intake.AnalysisIntakeChange;
import com.beez.backend.designitem.support.DesignJson;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Debezium JSON envelope for public.published_change_analysis; no payload contents are logged. */
@Component
@ConditionalOnProperty(prefix = "beez.cdc.intake", name = "enabled", havingValue = "true")
public final class PublishedAnalysisRecordDecoder implements AnalysisIntakeRecordDecoder {
    private static final int MAX_EVENT_CHARS = 1_048_576;

    @Override
    public AnalysisIntakeChange decode(String key, String value) {
        if (value == null || value.length() > MAX_EVENT_CHARS) throw invalid();
        final Object parsed;
        try { parsed = DesignJson.JSON.readValue(value, Object.class); }
        catch (RuntimeException malformed) { throw invalid(); }
        if (!(parsed instanceof Map<?, ?> root)) throw invalid();
        Object payload = root.containsKey("payload") ? root.get("payload") : root;
        if (!(payload instanceof Map<?, ?> event) || !(event.get("source") instanceof Map<?, ?> source)
                || !"public".equals(source.get("schema")) || !"published_change_analysis".equals(source.get("table"))
                || !(event.get("op") instanceof String operation)) throw invalid();
        Object image = "d".equals(operation) ? event.get("before") : event.get("after");
        if (!(image instanceof Map<?, ?> row)) throw invalid();
        Object rawId = row.get("id");
        if (!(rawId instanceof Number) || !rawId.toString().matches("[1-9][0-9]{0,18}")) throw invalid();
        try { if (Long.parseLong(rawId.toString()) < 1) throw invalid(); }
        catch (NumberFormatException tooLarge) { throw invalid(); }
        Object status = row.get("status");
        if (!"d".equals(operation) && !(status instanceof String)) throw invalid();
        return new AnalysisIntakeChange(rawId.toString(), operation, status instanceof String text ? text : null);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid intake event"); }
}
