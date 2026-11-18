"""Run local fault injections; all mutations are confined to the dedicated Compose project."""
import argparse
from datetime import datetime, timezone
import json
import http.cookiejar
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RT = HERE / ".runtime"
COMPOSE = ["docker", "compose", "-p", "beez-cdc-monitoring-229", "-f", str(RT / "compose.json")]
TOPIC = "beez.public.published_change_analysis"
GROUP = "beez-published-analysis"
CONNECTOR = "beez-published-analysis-1"
PHASE_A_ALERTS = {"CdcPendingDelay", "CdcFailedIntakes", "CdcBlockedProjects", "CdcExpiredLeases",
                  "CdcKafkaReceiveFailures", "CdcSlotInactive", "CdcConnectorNotRunning", "CdcConsumerLagStuck"}


def event(name, **details):
    record = {"utc": datetime.now(timezone.utc).isoformat(), "event": name, **details}
    with (RT / "events.jsonl").open("a", encoding="utf-8") as out:
        out.write(json.dumps(record, ensure_ascii=False) + "\n")
    print(json.dumps(record, ensure_ascii=True), flush=True)
    return record


def compose(*args, data=None, timeout=90, check=True):
    result = subprocess.run(COMPOSE + list(args), input=data, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError("Isolated compose operation failed: " + " ".join(args[:3]) + ": " + result.stderr[-1000:])
    return result.stdout


def sql(statement):
    return compose("exec", "-T", "postgres", "psql", "-U", "beez", "-d", "beez", "-v", "ON_ERROR_STOP=1", "-At", data=statement)


def request(url, data=None, method=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(url, body, headers={"Content-Type":"application/json"}, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        raw = response.read().decode()
        return json.loads(raw) if raw else None


def connect(path="", data=None, method=None):
    return request("http://127.0.0.1:18230/connectors" + path, data, method)


def query(expr):
    return request("http://127.0.0.1:19229/api/v1/query?" + urllib.parse.urlencode({"query":expr}))["data"]["result"]


def wait_for(check, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except Exception as error:
            last = type(error).__name__
        time.sleep(3)
    raise RuntimeError("Condition did not become true; last error type=" + str(last))


def health():
    return request("http://127.0.0.1:18229/actuator/health").get("status") == "UP"


def connector_config():
    config = json.loads((ROOT / "infra/cdc/dev/intake-connector.json").read_text(encoding="utf-8"))
    config.update({"database.user":"beez", "database.password":"qa229-only"})
    return config


def snapshot(label):
    alerts = request("http://127.0.0.1:19229/api/v1/alerts")["data"]["alerts"]
    clean = [{"name":a["labels"]["alertname"], "state":a["state"], "active_at":a["activeAt"], "value":a["value"]} for a in alerts]
    values = {}
    for expr in ["beez_cdc_analysis_snapshot_available", "beez_cdc_analysis_snapshot_last_success_epoch_seconds",
                 'up{job=~"backend|connect|postgres|kafka"}', "beez_cdc_kafka_consumer_up", "beez_cdc:kafka_reachable",
                 "beez_cdc_analysis_intakes", "beez_cdc_analysis_expired_leases", "beez_cdc_analysis_blocked_projects",
                 "beez_cdc_analysis_oldest_pending_seconds", 'alertmanager_notifications_total{integration="mattermost"}',
                 'alertmanager_notifications_failed_total{integration="mattermost"}']:
        values[expr] = [{"labels":v["metric"], "value":v["value"][1]} for v in query(expr)]
    return event("snapshot", label=label, alerts=clean, values=values)


def observe(seconds, label):
    end = time.monotonic() + seconds
    while True:
        snapshot(label)
        remaining = end - time.monotonic()
        if remaining <= 0:
            return
        time.sleep(min(30, remaining))


def assert_alerts(names, firing=True):
    if not firing:
        assert_healthy_telemetry()
    current = request("http://127.0.0.1:19229/api/v1/alerts")["data"]["alerts"]
    active = {a["labels"]["alertname"] for a in current if a["state"] == "firing"}
    wrong = names-active if firing else names&active
    if wrong:
        raise AssertionError("Alert state mismatch: " + ",".join(sorted(wrong)))
    failures = sum(float(x["value"][1]) for x in query('alertmanager_notifications_failed_total{integration="mattermost"}'))
    sent = sum(float(x["value"][1]) for x in query('alertmanager_notifications_total{integration="mattermost"}'))
    if failures != 0 or sent == 0:
        raise AssertionError(f"Mattermost delivery counters invalid: sent={sent}, failed={failures}")
    event("assertion_passed", assertion="alerts_firing" if firing else "alerts_resolved", names=sorted(names),
          mattermost_deliveries=sent, mattermost_failures=failures,
          caveat="Successful receiver requests are paired with separate actual-channel UI receipt verification")


def assert_healthy_telemetry():
    """Absence is recovery only when the backend and all CDC sources are fresh and healthy."""
    if not health():
        raise AssertionError("Backend health is not UP")
    for expression in ['up{job="backend"}', 'up{job="connect"}', 'up{job="postgres"}',
                       "beez_cdc_analysis_snapshot_available", "beez_cdc_kafka_consumer_up", "beez_cdc:kafka_reachable",
                       'pg_replication_slots_slot_is_active{slot_name="beez_published_analysis"}']:
        values = query(expression)
        if not values or not all(float(x["value"][1]) == 1 for x in values):
            raise AssertionError("Telemetry source unhealthy or absent: " + expression)
    freshness = query("time() - beez_cdc_analysis_snapshot_last_success_epoch_seconds")
    if not freshness or not all(0 <= float(x["value"][1]) < 60 for x in freshness):
        raise AssertionError("Database metrics snapshot is missing or stale")
    status = connect("/"+CONNECTOR+"/status")
    if status["connector"]["state"] != "RUNNING" or not status["tasks"] or any(t["state"] != "RUNNING" for t in status["tasks"]):
        raise AssertionError("Connector/task is not healthy")
    event("healthy_telemetry_verified", snapshot_age_seconds=[float(x["value"][1]) for x in freshness],
          backend="UP", connector="RUNNING", tasks="RUNNING", kafka="reachable", consumer="UP")


def producer(text):
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092", "--topic", TOPIC,
            data=text + "\n")


def verify_loaded_rule_timing():
    expected = {
        "CdcPendingDelay":(300,600), "CdcFailedIntakes":(0,600), "CdcBlockedProjects":(600,600),
        "CdcExpiredLeases":(300,600), "CdcKafkaReceiveFailures":(0,0), "CdcSlotInactive":(600,0),
        "CdcConnectorNotRunning":(300,0), "CdcConsumerLagStuck":(300,0), "CdcConsumerDown":(300,0),
        "CdcKafkaUnreachable":(300,0), "CdcConnectDown":(300,0), "CdcConnectorMissing":(600,0),
        "CdcSnapshotFailing":(300,0)}
    loaded = {r["name"]:r for group in request("http://127.0.0.1:19229/api/v1/rules")["data"]["groups"] for r in group["rules"]}
    observed = {}
    for name,timing in expected.items():
        rule = loaded[name]
        if (rule["duration"],rule["keepFiringFor"]) != timing or rule["health"] != "ok":
            raise AssertionError("Loaded production rule timing/health differs: " + name)
        observed[name] = {"for_seconds":rule["duration"], "keep_firing_for_seconds":rule["keepFiringFor"]}
    event("loaded_production_rule_timing_verified", rules=observed)


def init():
    wait_for(health, 240)
    wait_for(lambda: connect() is not None, 180)
    compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "kafka:9092", "--create", "--if-not-exists", "--topic", TOPIC,
            "--partitions", "1", "--replication-factor", "1")
    sql("CREATE PUBLICATION beez_published_analysis_cdc FOR TABLE published_change_analysis;\n")
    connect("/" + CONNECTOR + "/config", connector_config(), "PUT")
    wait_for(lambda: connect("/"+CONNECTOR+"/status")["tasks"] and connect("/"+CONNECTOR+"/status")["tasks"][0]["state"] == "RUNNING")
    sql("""
        INSERT INTO account(id,email,password_hash,display_name) OVERRIDING SYSTEM VALUE VALUES(229001,'qa229@example.invalid','unused','QA229');
        INSERT INTO project(id,name,owner_account_id) OVERRIDING SYSTEM VALUE VALUES(229001,'QA229 isolated fixtures',229001);
        INSERT INTO project_member(project_id,account_id,role) VALUES(229001,229001,'LEADER');
        INSERT INTO baseline(id,project_id,version_no,published_by) OVERRIDING SYSTEM VALUE VALUES(229001,229001,1,229001);
        INSERT INTO published_change_analysis(id,project_id,published_baseline_id,status,finished_at) OVERRIDING SYSTEM VALUE
          VALUES(229001,229001,229001,'NOT_EVALUATED',clock_timestamp());
    """)
    wait_for(lambda: any(float(x["value"][1]) == 1 for x in query("beez_cdc_kafka_consumer_up")), 120)
    event("initialized", connector=CONNECTOR, production_rules=True)
    loaded = request("http://127.0.0.1:19230/api/v2/status")["config"]["original"]
    if "CdcFailedIntakes|CdcNotEvaluatedRequiresReview" not in loaded or not any(x in loaded for x in ("repeat_interval: 24h", "repeat_interval: 1d")):
        raise AssertionError("FAILED repeat route is not loaded")
    routed = compose("exec", "-T", "alertmanager", "amtool", "config", "routes", "test", "--config.file=/etc/alertmanager/alertmanager.yml", "alertname=CdcFailedIntakes").strip()
    if routed != "mattermost":
        raise AssertionError("FAILED notification route does not select Mattermost")
    event("failed_repeat_route_verified", receiver=routed, repeat_interval="24h", wall_clock_resend_tested=False)
    verify_loaded_rule_timing()
    snapshot("baseline")


def seed_states():
    # Durable fixture rows exercise the real DB metrics reader; no fabricated Prometheus metrics.
    # The predecessor FAILED row keeps later PENDING and expired RUNNING rows present despite real recovery scans.
    sql("""
      INSERT INTO baseline(id,project_id,version_no,published_by) OVERRIDING SYSTEM VALUE
        VALUES(229002,229001,2,229001),(229003,229001,3,229001),(229004,229001,4,229001);
      INSERT INTO published_change_analysis(id,project_id,published_baseline_id,status,attempt,error_code,finished_at)
        OVERRIDING SYSTEM VALUE VALUES(229002,229001,229002,'FAILED',4,'model_unavailable',clock_timestamp());
      INSERT INTO published_change_analysis(id,project_id,published_baseline_id,status)
        OVERRIDING SYSTEM VALUE VALUES(229003,229001,229003,'PENDING');
      INSERT INTO published_change_analysis(id,project_id,published_baseline_id,status,attempt,lease_until,started_at)
        OVERRIDING SYSTEM VALUE VALUES(229004,229001,229004,'RUNNING',1,clock_timestamp()-interval '1 second',clock_timestamp()-interval '2 seconds');
    """)
    producer('{"qa229_invalid_record":true}')
    sql("SELECT pg_create_physical_replication_slot('qa229_incompatible_slot');")
    bad = connector_config()
    bad["slot.name"] = "qa229_incompatible_slot"
    connect("/"+CONNECTOR+"/config", bad, "PUT")
    wait_for(lambda: any(t["state"] == "FAILED" for t in connect("/"+CONNECTOR+"/status")["tasks"]), 120)
    event("phase_a_started", fixtures=["FAILED after retry budget", "new PENDING blocked by predecessor", "expired RUNNING blocked by predecessor"],
          actual_faults=["malformed Kafka record blocks partition", "connector restarted against incompatible physical slot"],
          caveat="DB rows model stored failure/lease states; these rows do not themselves prove a worker process crash or model retry execution")
    observe(16*60, "phase_a_real_thresholds")
    assert_alerts(PHASE_A_ALERTS)


def clear_states():
    # Mark the owned isolated fixtures terminal; preserve row history, no table truncation/deletion.
    sql("UPDATE published_change_analysis SET status='COMPLETED',evaluation_status='COMPLETED',lease_until=NULL,error_code=NULL,finished_at=clock_timestamp() WHERE id BETWEEN 229002 AND 229004;")
    connect("/"+CONNECTOR+"/config", connector_config(), "PUT")
    wait_for(lambda: any(t["state"] == "RUNNING" for t in connect("/"+CONNECTOR+"/status")["tasks"]))
    # Skip only the intentionally malformed test record after stopping this isolated consumer group.
    try:
        compose("stop", "-t", "1", "backend")
        time.sleep(3)
        compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092", "--group", GROUP,
                "--topic", TOPIC, "--reset-offsets", "--to-latest", "--execute")
    finally:
        compose("up", "-d", "--no-deps", "backend")
    wait_for(health, 180)
    wait_for(lambda: any(float(x["value"][1]) == 1 for x in query("beez_cdc_kafka_consumer_up")),120)
    event("phase_a_recovered", recovery="fixture rows terminal, malformed isolated offset skipped with group stopped, valid connector restored")


def broker_fault(mode):
    if mode not in ("pause", "stop"):
        raise ValueError(mode)
    try:
        compose(mode, "kafka")
        event("broker_fault", mode=mode)
        compose("restart", "-t", "1", "backend")
        wait_for(health, 180)
        event("backend_healthy_without_broker", mode=mode)
        if mode == "pause":
            resolved = bool(compose("exec", "-T", "backend", "getent", "hosts", "kafka").strip())
            if not resolved:
                raise AssertionError("Paused broker DNS did not resolve")
            event("paused_broker_dns_resolved", hostname="kafka", resolved=True,
                  command="docker compose exec -T backend getent hosts kafka")
        intake = publication_without_broker(mode)
        observe(6*60, "broker_"+mode)
        assert_alerts({"CdcConsumerDown", "CdcKafkaUnreachable"})
    finally:
        compose("unpause" if mode == "pause" else "start", "kafka")
        event("broker_restored", mode=mode)
    wait_for(lambda: any(float(x["value"][1]) == 1 for x in query("beez_cdc_kafka_consumer_up")),180)
    event("consumer_reconnected_without_backend_restart", mode=mode)
    observe(60,"broker_"+mode+"_reconnected")
    wait_for(lambda: (assert_healthy_telemetry(), True)[1], 420)
    assert_publications_still_complete([intake])
    assert_alerts({"CdcConsumerDown", "CdcKafkaUnreachable"}, firing=False)


def publication_without_broker(mode):
    # The coherent initial graph comes from the companion production-service fixture.
    # Each new proposal here is created and approved through the running backend HTTP API.
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def post(path, body):
        req = urllib.request.Request("http://127.0.0.1:18229"+path, json.dumps(body).encode(),
            headers={"Content-Type":"application/json", "Origin":"http://localhost:5173", "Idempotency-Key":"qa229-"+uuid.uuid4().hex})
        with opener.open(req, timeout=30) as res:
            return json.load(res)
    post("/api/auth/login", {"email":"leader@test","password":"fixture-pass-1"})
    base = sql("SELECT current_baseline_id FROM project WHERE id=1;").strip()
    version = sql("SELECT design_item_version_id FROM baseline_item WHERE baseline_id="+base+" AND design_item_id=1;").strip()
    proposal = post("/api/projects/1/change-proposals", {"base_baseline_id":base,"title":"QA229 broker "+mode,
        "reason":"Isolated broker outage recovery verification", "items":[{"operation":"UPDATE","design_item_id":"1", "base_design_item_version_id":version,
        "kind":"API_CONTRACT","schema_version":1,"proposed_content":{"method":"GET","path":"/users/qa229/"+mode,"description":"synthetic outage test"}}],"documents":[]})
    published = post("/api/projects/1/change-proposals/"+proposal["id"]+"/approve", {
        "expected_base_baseline_id":base,"expected_revision":proposal["revision"],"expected_snapshot_hash":proposal["snapshot_hash"],
        "impact_override_reason":"QA229 explicitly skips pre-approval AI to test post-publication recovery"})
    baseline = published["baseline"]["id"]
    intake = sql("SELECT id FROM published_change_analysis WHERE project_id=1 AND published_baseline_id="+baseline+";").strip()
    wait_for(lambda: sql("SELECT status FROM published_change_analysis WHERE id="+intake+";").strip()=="COMPLETED",120)
    status = sql("SELECT status||':'||evaluation_status||':'||attempt FROM published_change_analysis WHERE id="+intake+";").strip()
    attempts = int(sql("SELECT count(*) FROM published_analysis_attempt WHERE intake_id="+intake+";").strip())
    if attempts != 1:
        raise AssertionError("Unexpected number of durable publication attempts: " + str(attempts))
    event("valid_publication_completed_without_broker", mode=mode, intake=intake, state=status, attempt_records=attempts,
          transport="actual authenticated create/approve HTTP; actual recovery scheduler and database persistence", model="deterministic HTTP fixture")
    return intake


def assert_publications_still_complete(intakes):
    wanted = set(map(str, intakes))
    raw = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
                  "--bootstrap-server", "kafka:9092", "--topic", TOPIC, "--from-beginning",
                  "--timeout-ms", "5000", check=False, timeout=30)
    captured = {intake:set() for intake in sorted(wanted)}
    for line in raw.splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if not isinstance(record, dict):
            continue
        record = record.get("payload", record)
        if not isinstance(record, dict):
            continue
        after = record.get("after") or {}
        intake = str(after.get("id"))
        if intake in captured:
            captured[intake].add((record.get("op"),after.get("status")))
    if any(not {("c","PENDING"),("u","COMPLETED")} <= captured[intake] for intake in wanted):
        raise AssertionError("New publication create/completion records are not in Kafka")
    def committed_offsets_caught_up():
        raw = compose("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh",
                      "--bootstrap-server", "kafka:9092", "--group", GROUP, "--describe")
        rows = [line.split() for line in raw.splitlines()]
        rows = [r for r in rows if len(r) >= 6 and r[0] == GROUP and r[1] == TOPIC]
        return bool(rows) and all(r[3].isdigit() and r[4].isdigit() and int(r[3]) == int(r[4]) and r[5] == "0" for r in rows)
    wait_for(committed_offsets_caught_up, 120)
    def exported_lag_zero():
        values = query('kafka_consumergroup_lag_sum{consumergroup="'+GROUP+'"}')
        return bool(values) and all(float(x["value"][1]) == 0 for x in values)
    wait_for(exported_lag_zero, 60)
    details = []
    for intake in intakes:
        if not str(intake).isdigit():
            raise ValueError("Invalid owned intake ID")
        state = sql("SELECT status||':'||attempt FROM published_change_analysis WHERE id="+str(intake)+";").strip()
        attempts = int(sql("SELECT count(*) FROM published_analysis_attempt WHERE intake_id="+str(intake)+";").strip())
        if state != "COMPLETED:1" or attempts != 1:
            raise AssertionError("Publication changed after CDC catch-up: " + str(intake))
        details.append({"intake":str(intake), "status":"COMPLETED", "attempt":1, "attempt_records":attempts})
    event("publications_still_complete_after_cdc_catchup", intakes=details, consumer_group_lag=0,
          captured_kafka_records={key:sorted(values) for key,values in captured.items()},
          original_group_committed_offset_equals_log_end=True)


def connect_fault():
    # The optional DB aggregation-failure scenario runs only after state-alert recovery.
    # Rename one test-only schema table briefly so the real metrics query fails. Restore in finally.
    try:
        compose("stop", "-t", "1", "connect")
        event("connect_stopped")
        try:
            sql("ALTER TABLE published_analysis_attempt RENAME TO qa229_attempt_temporarily_unavailable;")
            event("metrics_query_table_temporarily_unavailable")
            observe(6*60, "connect_stopped_and_snapshot_failure")
            assert_alerts({"CdcConnectDown", "CdcSnapshotFailing"})
        finally:
            sql("""DO $$ BEGIN
                IF to_regclass('public.qa229_attempt_temporarily_unavailable') IS NOT NULL THEN
                    ALTER TABLE qa229_attempt_temporarily_unavailable RENAME TO published_analysis_attempt;
                END IF;
            END $$;""")
            event("metrics_query_table_restored")
        observe(5*60, "connect_stopped")
        assert_alerts({"CdcConnectDown", "CdcSlotInactive"})
    finally:
        compose("start", "connect")
        event("connect_restart_requested")
    wait_for(lambda: any(t["state"] == "RUNNING" for t in connect("/"+CONNECTOR+"/status")["tasks"]),180)
    event("connect_restarted")
    observe(6*60, "all_recovered")
    assert_alerts({"CdcConnectDown", "CdcSlotInactive", "CdcSnapshotFailing"}, firing=False)


def delete_connector():
    try:
        connect("/"+CONNECTOR, method="DELETE")
        event("isolated_connector_deleted")
        observe(11*60, "connector_deleted")
        assert_alerts({"CdcConnectorMissing", "CdcSlotInactive"})
    finally:
        connect("/"+CONNECTOR+"/config", connector_config(), "PUT")
        event("connector_restore_requested")
    wait_for(lambda: any(t["state"] == "RUNNING" for t in connect("/"+CONNECTOR+"/status")["tasks"]),180)
    event("isolated_connector_recreated")
    observe(6*60, "connector_restored")
    assert_alerts({"CdcConnectorMissing", "CdcSlotInactive"}, firing=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["init","seed","clear","pause","stop","connect","delete","verify-a","verify-final","snapshot","observe","all"])
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if args.action == "init": init()
    elif args.action == "seed": seed_states()
    elif args.action == "clear": clear_states()
    elif args.action in ("pause","stop"): broker_fault(args.action)
    elif args.action == "connect": connect_fault()
    elif args.action == "delete": delete_connector()
    elif args.action == "verify-a": assert_alerts(PHASE_A_ALERTS)
    elif args.action == "verify-final": assert_alerts(PHASE_A_ALERTS|{"CdcConsumerDown","CdcKafkaUnreachable","CdcConnectDown","CdcConnectorMissing","CdcSnapshotFailing"}, firing=False)
    elif args.action == "snapshot": snapshot("manual")
    elif args.action == "observe": observe(args.seconds,"manual")
    else:
        if not os.environ.get("BEEZ_229_PUBLICATION_FIXTURE"):
            raise RuntimeError("BEEZ_229_PUBLICATION_FIXTURE must point to the companion synthetic recovery-test export")
        init()
        subprocess.run([sys.executable,str(HERE / "import_fixture.py")],check=True)
        try:
            seed_states()
        finally:
            clear_states()
        broker_fault("pause"); broker_fault("stop")
        assert_alerts(PHASE_A_ALERTS, firing=False)
        connect_fault(); delete_connector()
        assert_alerts(PHASE_A_ALERTS|{"CdcConsumerDown","CdcKafkaUnreachable","CdcConnectDown","CdcConnectorMissing","CdcSnapshotFailing"}, firing=False)
        event("completed")

if __name__ == "__main__":
    main()
