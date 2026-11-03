alter table validation_finding
    add column policy_need_id text,
    add column conflicting_design_refs jsonb not null default '[]'::jsonb,
    add constraint validation_finding_conflicting_design_refs_array
        check (jsonb_typeof(conflicting_design_refs) = 'array');
