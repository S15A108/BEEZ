-- S15P31A108-390. A project's initial backlog records the approved baseline it came from.
CREATE TABLE task_breakdown_batch (
    project_id BIGINT PRIMARY KEY,
    baseline_id BIGINT NOT NULL,
    FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id)
);

CREATE TABLE task_breakdown_task (
    project_id BIGINT NOT NULL REFERENCES task_breakdown_batch(project_id),
    task_id BIGINT NOT NULL UNIQUE,
    position INTEGER NOT NULL CHECK (position > 0),
    PRIMARY KEY (project_id, position),
    FOREIGN KEY (task_id, project_id) REFERENCES task(id, project_id)
);
