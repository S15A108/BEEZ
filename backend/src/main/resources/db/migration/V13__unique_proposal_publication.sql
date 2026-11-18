-- One proposal can publish only one baseline.
-- First publications (NULL proposal) remain unrestricted.
CREATE UNIQUE INDEX ux_baseline_change_proposal ON baseline(change_proposal_id)
    WHERE change_proposal_id IS NOT NULL;
