package com.beez.backend.validation.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Checks explicit, persisted facts only. The caller owns authorization, locking and final decision. */
@Service
public class ValidationRuleChecker {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public ValidationRuleChecker(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record Finding(String kind, String severity, String message, Long designItemId,
                          Long designItemVersionId, Long relatedTaskId, String resolution, String nextAction) {}
    public record Result(List<Finding> findings, List<String> checkedRules, List<String> unsupportedRules) {
        public Result { findings = List.copyOf(findings); checkedRules = List.copyOf(checkedRules); unsupportedRules = List.copyOf(unsupportedRules); }
        /** Other mandatory checks and the semantic analysis are not established by this component. */
        public boolean allRulesVerified() { return unsupportedRules.isEmpty(); }
    }
    record Ref(long itemId, long versionId, String relation, Long currentVersion) {}
    record Conflict(long itemId, long versionId, long relatedTaskId) {}
    record Facts(String title, String goal, String approach, boolean noContractChange, boolean unlinkedEmpty,
                 List<Ref> refs, List<Long> dependencies, Set<Long> sameProjectDependencies,
                 Set<Long> cyclicDependencies, List<Conflict> conflicts, long taskId) {}

    /** The baseline and revision must still be current; a stale snapshot is never a PASS. */
    public Result check(long project, long task, long revision, long baseline) {
        if (project <= 0 || task <= 0 || revision <= 0 || baseline <= 0) throw new IllegalArgumentException("invalid validation context");
        var plans = jdbc.sql("""
                select r.title,r.goal,r.approach,r.no_contract_change,r.unlinked::text as unlinked,
                       r.dependency_task_ids::text as dependency_task_ids
                  from project p join task t on t.project_id=p.id
                  join task_plan_revision r on r.id=t.current_plan_revision_id and r.task_id=t.id
                 where p.id=:project and p.current_baseline_id=:baseline and t.id=:task and r.id=:revision
                """).param("project", project).param("baseline", baseline).param("task", task).param("revision", revision)
                .query().listOfRows();
        if (plans.size() != 1) throw new IllegalStateException("validation snapshot is no longer current");
        var plan = plans.getFirst();
        List<?> unlinked = array(plan.get("unlinked"));
        var dependencies = new ArrayList<Long>();
        for (Object id : array(plan.get("dependency_task_ids"))) {
            try { long parsed = Long.parseLong(id.toString()); if (parsed <= 0) throw new IllegalArgumentException(); dependencies.add(parsed); }
            catch (RuntimeException e) { throw new IllegalStateException("invalid stored dependency", e); }
        }
        var refs = jdbc.sql("""
                select r.design_item_id,r.design_item_version_id,r.relation,bi.design_item_version_id as current_version
                from task_plan_design_ref r left join baseline_item bi
                  on bi.baseline_id=:baseline and bi.project_id=:project and bi.design_item_id=r.design_item_id
                where r.plan_revision_id=:revision order by r.design_item_id
                """).param("baseline", baseline).param("project", project).param("revision", revision).query().listOfRows().stream()
                .map(row -> new Ref(number(row.get("design_item_id")), number(row.get("design_item_version_id")),
                        (String) row.get("relation"), nullableNumber(row.get("current_version")))).toList();
        Set<Long> sameProject = new HashSet<>(), cyclicDependencies = Set.of();
        if (!dependencies.isEmpty()) {
            var ids = new LinkedHashSet<>(dependencies);
            jdbc.sql("select id from task where project_id=:project and id in (:ids)")
                    .param("project", project).param("ids", ids).query(Long.class).list().forEach(sameProject::add);
            // Follow all same-project BLOCKS edges to find cycles of any length that
            // return to this plan's task. The visited set also handles pre-existing cycles.
            Map<Long, List<Long>> graph = new HashMap<>();
            for (var edge : jdbc.sql("select task_id,depends_on_task_id from task_dependency where project_id=:project and kind='BLOCKS'")
                    .param("project", project).query().listOfRows()) {
                graph.computeIfAbsent(number(edge.get("task_id")), ignored -> new ArrayList<>())
                        .add(number(edge.get("depends_on_task_id")));
            }
            cyclicDependencies = cyclicDependencies(task, dependencies, graph);
        }
        var conflicts = jdbc.sql("""
                select distinct target.design_item_id,target.design_item_version_id,other.id as other_task
                  from task_plan_design_ref target
                  join task_plan_design_ref other_ref on other_ref.design_item_id=target.design_item_id and other_ref.relation='CHANGES'
                  join task other on other.current_plan_revision_id=other_ref.plan_revision_id and other.project_id=:project
                 where target.plan_revision_id=:revision and target.relation='CHANGES'
                   and other.id<>:task and other.status in ('IN_PROGRESS','WAITING')
                 order by target.design_item_id,other.id
                """).param("project", project).param("revision", revision).param("task", task).query().listOfRows().stream()
                .map(row -> new Conflict(number(row.get("design_item_id")), number(row.get("design_item_version_id")),
                        number(row.get("other_task")))).toList();
        // The caller must protect this snapshot against later changes while storing a decision.
        var stillCurrent = jdbc.sql("""
                select count(*) from project p join task t on t.project_id=p.id
                 where p.id=:project and p.current_baseline_id=:baseline and t.id=:task and t.current_plan_revision_id=:revision
                """).param("project", project).param("baseline", baseline).param("task", task).param("revision", revision)
                .query(Long.class).single();
        if (stillCurrent != 1) throw new IllegalStateException("validation snapshot changed");
        return evaluate(new Facts((String) plan.get("title"), (String) plan.get("goal"), (String) plan.get("approach"),
                Boolean.TRUE.equals(plan.get("no_contract_change")), unlinked.isEmpty(), refs, dependencies,
                sameProject, cyclicDependencies, conflicts, task));
    }

    static Result evaluate(Facts facts) {
        List<Finding> findings = new ArrayList<>();
        if (blank(facts.title()) || blank(facts.goal()) || blank(facts.approach())) {
            findings.add(input("작업 제목·목표·접근 방식의 필수 정보가 누락되었습니다.", null, null, null));
        }
        boolean changes = facts.refs().stream().anyMatch(ref -> "CHANGES".equals(ref.relation()));
        if (!facts.noContractChange() && !changes && facts.unlinkedEmpty()) {
            findings.add(input("계약 변경 없음 선언이 없고 변경 참조나 미연결 내용도 없습니다.", null, null, null));
        }
        if (facts.noContractChange() && changes) {
            findings.add(input("계약 변경 없음 선언과 CHANGES 설계 참조가 함께 있습니다.", null, null, null));
        }
        for (Ref ref : facts.refs()) {
            if (!Objects.equals(ref.versionId(), ref.currentVersion())) {
                findings.add(input("참조 설계 항목이 현재 기준에 없거나 참조 버전이 다릅니다.", ref.itemId(), ref.versionId(), null));
            }
        }
        Set<Long> seen = new HashSet<>();
        for (Long dependency : facts.dependencies()) {
            if (!seen.add(dependency) || dependency == facts.taskId() || !facts.sameProjectDependencies().contains(dependency)
                    || facts.cyclicDependencies().contains(dependency)) {
                Long related = facts.sameProjectDependencies().contains(dependency) && dependency != facts.taskId() ? dependency : null;
                findings.add(input("선행 작업 참조 " + dependency + "이(가) 중복·자기 참조·다른 프로젝트이거나 순환합니다.", null, null, related));
            }
        }
        for (Conflict conflict : facts.conflicts()) {
            findings.add(new Finding("CONFLICT", "BLOCKING", "다른 진행 작업이 같은 설계 항목을 변경 중입니다.",
                    conflict.itemId(), conflict.versionId(), conflict.relatedTaskId(), "HUMAN_REVIEW", "작업 담당자와 변경 범위를 조정하세요."));
        }
        List<String> unsupported = new ArrayList<>();
        // No structured change proposal exists in this narrowly defined case. Natural-language
        // changes still require AI semantic analysis; this does not certify their absence.
        if (changes || !facts.noContractChange()) unsupported.add("STRUCTURED_CONTRACT_MISMATCH");
        // The free-form unlinked schema is undecided; do not claim its contents were checked.
        if (!facts.unlinkedEmpty()) unsupported.add("UNRESOLVED_Q02_RULES");
        if (facts.refs().isEmpty()) unsupported.add("NO_EXPLICIT_DESIGN");
        return new Result(findings, List.of("REQUIRED_FIELDS", "CONTRACT_CHANGE_DECLARATION", "DESIGN_REFERENCE_VERSION",
                "EXPLICIT_DEPENDENCY", "DEPENDENCY_TRANSITIVE_CYCLE", "ACTIVE_CHANGES_CONFLICT"), unsupported);
    }

    /** Returns plan dependency roots from which the target task is reachable. */
    static Set<Long> cyclicDependencies(long target, List<Long> roots, Map<Long, List<Long>> graph) {
        Set<Long> cyclic = new LinkedHashSet<>();
        for (Long root : roots) {
            if (root == null) throw new IllegalArgumentException("null dependency");
            var pending = new ArrayDeque<Long>();
            Set<Long> visited = new HashSet<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                long node = pending.removeFirst();
                if (node == target) { cyclic.add(root); break; }
                if (visited.add(node)) pending.addAll(graph.getOrDefault(node, List.of()));
            }
        }
        return cyclic;
    }

    private static Finding input(String message, Long item, Long version, Long task) {
        return new Finding("MISSING_INPUT", "BLOCKING", message, item, version, task, "FIX_PLAN", "작업 계획의 해당 항목을 보완하세요.");
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private List<?> array(Object value) {
        try { return json.readValue(Objects.toString(value), new TypeReference<List<Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("invalid stored plan JSON", e); }
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static Long nullableNumber(Object value) { return value == null ? null : number(value); }
}
