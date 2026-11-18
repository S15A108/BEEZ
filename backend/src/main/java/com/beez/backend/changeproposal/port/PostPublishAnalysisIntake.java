package com.beez.backend.changeproposal.port;

/** #197 inserts a unique intake row in the publication transaction; CDC consumes only committed rows. */
public interface PostPublishAnalysisIntake {
    void accept(long projectId, long publishedBaselineId, Long changeProposalId);
}
