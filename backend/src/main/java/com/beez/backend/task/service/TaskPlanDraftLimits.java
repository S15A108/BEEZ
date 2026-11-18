package com.beez.backend.task.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Operational input budgets, bounded by the internal AI request contract. */
@ConfigurationProperties("beez.task-plan-draft")
public record TaskPlanDraftLimits(
        @DefaultValue("12") int retrievalLimit,
        @DefaultValue("4000") int maxExcerptChars,
        @DefaultValue("12000") int totalExcerptChars,
        @DefaultValue("100") int maxDesignItems,
        @DefaultValue("300") int designSummaryChars,
        @DefaultValue("6") int selectedExcerpts,
        @DefaultValue("0.15") double similarityDrop,
        @DefaultValue("0.2") double lexicalWeight) {
    public TaskPlanDraftLimits(int retrievalLimit, int maxExcerptChars, int totalExcerptChars,
                              int maxDesignItems, int designSummaryChars) {
        this(retrievalLimit, maxExcerptChars, totalExcerptChars, maxDesignItems, designSummaryChars, 6, 0.15, 0.2);
    }
    @ConstructorBinding
    public TaskPlanDraftLimits {
        if (retrievalLimit < 1 || retrievalLimit > 12
                || maxExcerptChars < 1 || maxExcerptChars > 4000
                || totalExcerptChars < maxExcerptChars
                || maxDesignItems < 0 || maxDesignItems > 100
                || designSummaryChars < 1 || designSummaryChars > 300
                || selectedExcerpts < 1 || selectedExcerpts > 12
                || !Double.isFinite(similarityDrop) || similarityDrop < 0 || similarityDrop > 2
                || !Double.isFinite(lexicalWeight) || lexicalWeight < 0 || lexicalWeight > 1) {
            throw new IllegalArgumentException("Invalid task plan draft input budgets (AI contract limits)");
        }
    }
}
