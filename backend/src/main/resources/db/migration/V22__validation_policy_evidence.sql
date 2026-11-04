alter table validation_finding
    add column policy_need_id text,
    add column conflicting_design_refs jsonb not null default '[]'::jsonb,
    add constraint validation_finding_conflicting_design_refs_array
        check (jsonb_typeof(conflicting_design_refs) = 'array');

-- These are the approved versions actually cited by validated policy assessments,
-- not all search candidates and not declared task-plan references.
create table validation_policy_ref (
    validation_id bigint not null references validation(id),
    design_item_id bigint not null,
    design_item_version_id bigint not null,
    primary key (validation_id, design_item_id),
    foreign key (design_item_version_id, design_item_id) references design_item_version(id, design_item_id)
);
create trigger trg_vpr_immutable before update on validation_policy_ref for each row execute function forbid_update();
create index ix_vpr_item on validation_policy_ref(design_item_id);
