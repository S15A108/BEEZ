"""Convert sanitized observation records into reviewable evidence (no secrets or raw app logs)."""
from datetime import datetime
import json
from pathlib import Path
from control import PHASE_A_ALERTS

HERE = Path(__file__).resolve().parent
RT = HERE / ".runtime"


def main():
    events = [json.loads(line) for line in (RT / "events.jsonl").read_text(encoding="utf-8").splitlines()]
    observations = [e for e in events if e["event"] == "snapshot"]
    alerts = {}
    for e in observations:
        for a in e["alerts"]:
            record = alerts.setdefault(a["name"], {"first_observed_pending":None, "first_observed_firing":None, "last_observed_firing":None})
            if a["state"] == "pending" and record["first_observed_pending"] is None:
                record["first_observed_pending"] = e["utc"]
            if a["state"] == "firing":
                if record["first_observed_firing"] is None:
                    record["first_observed_firing"] = e["utc"]
                    record["condition_active_at"] = a["active_at"]
                    record["observed_seconds_after_active"] = round((datetime.fromisoformat(e["utc"])-datetime.fromisoformat(a["active_at"].replace("Z","+00:00"))).total_seconds(),1)
                record["last_observed_firing"] = e["utc"]
    final = observations[-1]
    sent = sum(float(x["value"]) for x in final["values"].get('alertmanager_notifications_total{integration="mattermost"}',[]))
    failed = sum(float(x["value"]) for x in final["values"].get('alertmanager_notifications_failed_total{integration="mattermost"}',[]))
    expected = PHASE_A_ALERTS | {"CdcConsumerDown", "CdcKafkaUnreachable", "CdcConnectDown", "CdcConnectorMissing", "CdcSnapshotFailing"}
    assertions = [e for e in events if e["event"] == "assertion_passed"]
    firing = {name for e in assertions if e["assertion"] == "alerts_firing" for name in e["names"]}
    resolved = {name for e in assertions if e["assertion"] == "alerts_resolved" for name in e["names"]}
    finished = any(e["event"] == "completed" for e in events)
    wrapup = next((e for e in reversed(events) if e["event"] == "user_requested_wrapup"), None)
    summary = {"environment":"isolated local Docker Compose; synthetic DB fixtures; actual application jar, Kafka, Debezium, PostgreSQL, Prometheus, Alertmanager and Mattermost",
               "started_at":events[0]["utc"],"last_observed_at":final["utc"],"production_thresholds_unchanged":True,
               "provenance":json.loads((RT / "provenance.json").read_text()),
               "automated_scenarios_complete":finished and expected <= firing and expected <= resolved,
               "expected_alerts":sorted(expected),
               "execution_stopped_by_user":wrapup is not None,
               "not_executed_by_user_request":[] if wrapup is None else wrapup["skipped"],
               "completed_alert_families":sorted(expected & firing & resolved),
               "missing_firing_assertions":sorted(expected-firing), "missing_resolved_assertions":sorted(expected-resolved),
               "alerts":alerts,"assertions":assertions,
               "lifecycle_events":[e for e in events if e["event"] not in ("snapshot","assertion_passed")],
               "mattermost_receiver_requests":sent,"mattermost_receiver_failures":failed,
               "last_firing_alerts":[a["name"] for a in final["alerts"] if a["state"] == "firing"],
               "limitations":["24-hour repeat route is loaded and inspected; no 24-hour wall-clock wait is claimed.",
                              "Phase-A failure/lease rows are persisted-state fixtures, separate from actual worker-crash/model-retry tests.",
                              "Receiver HTTP success must be paired with the separately recorded actual-channel receipt links."]}
    (HERE / "results.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("Wrote sanitized results.json; execution is complete only when required assertions and receipt verification exist")


if __name__ == "__main__":
    main()
