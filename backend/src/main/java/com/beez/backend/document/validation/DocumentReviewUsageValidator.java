package com.beez.backend.document.validation;

import java.math.BigDecimal;
import java.util.*;

/** Admit only bounded, caller-scoped common Usage fields, including failed real calls. */
public final class DocumentReviewUsageValidator {
    private DocumentReviewUsageValidator() { }

    public static List<Map<String, Object>> extract(Object value, String requestKey) {
        if (!(value instanceof List<?> list) || list.size() > 20) return List.of();
        var result = new ArrayList<Map<String, Object>>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw) || !(raw.get("request_key") instanceof String key)
                    || key.length() > 128 || !(key.equals(requestKey) || key.startsWith(requestKey + "-"))
                    || !"BEE_DRAFT".equals(raw.get("feature")) || !(raw.get("model") instanceof String model)
                    || model.isBlank() || model.length() > 200 || !(raw.get("failed") instanceof Boolean)
                    || !(raw.get("tokens_known") instanceof Boolean known) || !integer(raw.get("latency_ms"))) continue;
            if (known && (!integer(raw.get("input_tokens")) || !integer(raw.get("output_tokens")))) continue;
            if (raw.get("cache_tokens") != null && !integer(raw.get("cache_tokens"))) continue;
            var item = new HashMap<String, Object>();
            for (String field : List.of("request_key", "feature", "model", "failed", "tokens_known", "latency_ms")) item.put(field, raw.get(field));
            if (known) { item.put("input_tokens", raw.get("input_tokens")); item.put("output_tokens", raw.get("output_tokens")); }
            if (raw.get("cache_tokens") != null) item.put("cache_tokens", raw.get("cache_tokens"));
            if (raw.get("provider") instanceof String provider && !provider.isBlank() && provider.length() <= 100) item.put("provider", provider);
            if (raw.get("estimated_cost_amount") != null) {
                try {
                    BigDecimal cost = new BigDecimal(raw.get("estimated_cost_amount").toString());
                    if (cost.signum() < 0 || cost.scale() > 6 || cost.compareTo(new BigDecimal("1000000")) >= 0
                            || !(raw.get("currency") instanceof String currency) || !currency.matches("[A-Z]{3}")) continue;
                    item.put("estimated_cost_amount", cost); item.put("currency", currency);
                } catch (NumberFormatException e) { continue; }
            }
            result.add(item);
        }
        return result;
    }

    private static boolean integer(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.intValue() && n.intValue() >= 0;
    }
}
