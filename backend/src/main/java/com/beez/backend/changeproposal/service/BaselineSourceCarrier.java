package com.beez.backend.changeproposal.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.id;

import com.beez.backend.changeproposal.repository.PublicationRepository;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.support.SourceRemapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * 발행하는 새 기준에 설계 항목의 유효 출처를 기록한다(S15P31A108-406).
 *
 * <p>항목 버전은 불변이라 옛 문서 버전을 가리키고, 근거 수집 같은 읽는 쪽은 "출처 문서 버전 = 기준의 문서 버전" 이어야 원문을 찾는다.
 * 그래서 직전 기준의 유효 출처에서 출발해, 출처 문서의 새 기준 버전이 달라졌으면 {@link SourceRemapper} 로 줄 위치를 옮기고,
 * 달라지지 않았으면 직전 기준에 덮어쓴 행을 그대로 이어받는다(이어받지 않으면 항목 버전의 옛 출처로 되돌아간다).
 * 이미 {@code NEEDS_CONFIRMATION} 인 항목은 인용 줄이 증명된 적이 없으므로 다시 옮기지 않고 문서 버전만 따라간다.
 * 항목 단위의 이상(본문 누락·줄 수 초과 등)은 예외가 아니라 {@code NEEDS_CONFIRMATION} 이다 — 발행을 막지 않는다.
 */
@Component
public class BaselineSourceCarrier {
    private static final Logger log = LoggerFactory.getLogger(BaselineSourceCarrier.class);
    static final String REMAPPED = "REMAPPED";
    static final String NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";

    private final PublicationRepository repository;
    private final BaselineRepository baselines;

    public BaselineSourceCarrier(PublicationRepository repository, BaselineRepository baselines) {
        this.repository = repository;
        this.baselines = baselines;
    }

    /**
     * @param itemsInBaseline 새 기준에 들어간 항목 id(baseline_item 행이 이미 있어야 한다)
     * @param targetVersions  문서 id → 새 기준에서의 문서 버전 id
     */
    public void carry(long project, long previousBaseline, long baseline, Set<Long> itemsInBaseline,
                      Map<Long, Long> targetVersions) {
        Map<String, SourceRemapper> remappers = new HashMap<>();
        Map<Long, String> contents = new HashMap<>();
        int remapped = 0, needsConfirmation = 0, carried = 0;
        for (Map<String, Object> row : repository.effectiveSources(project, previousBaseline)) {
            long item = id(row, "design_item_id");
            if (!itemsInBaseline.contains(item)) continue;
            long from = id(row, "source_document_version_id");
            Long target = targetVersions.get(id(row, "document_id"));
            String status = (String) row.get("source_status");
            String locator = (String) row.get("source_locator");
            if (target == null) continue;
            if (target == from) {
                if (status != null) {
                    long origin = row.get("remapped_from_document_version_id") == null
                            ? from : id(row, "remapped_from_document_version_id");
                    baselines.insertItemSource(baseline, project, item, from, locator, status, origin);
                    carried++;
                }
                continue;
            }
            if (NEEDS_CONFIRMATION.equals(status)) {
                // 줄 번호(locator)는 처음 확인 필요가 된 버전 기준 그대로이므로 remapped_from 도 그 버전을 유지한다.
                long locatorVersion = row.get("remapped_from_document_version_id") == null
                        ? from : id(row, "remapped_from_document_version_id");
                baselines.insertItemSource(baseline, project, item, target, locator, NEEDS_CONFIRMATION, locatorVersion);
                needsConfirmation++;
                continue;
            }
            SourceRemapper remapper = remappers.computeIfAbsent(from + ">" + target, key -> SourceRemapper.of(
                    contents.computeIfAbsent(from, repository::documentContent),
                    contents.computeIfAbsent(target, repository::documentContent)));
            SourceRemapper.Result result = remap(remapper, locator);
            if (result instanceof SourceRemapper.Remapped moved) {
                baselines.insertItemSource(baseline, project, item, target, JSON.writeValueAsString(moved.locator()),
                        REMAPPED, from);
                remapped++;
            } else {
                log.info("baseline {} item {} source needs confirmation: {}", baseline, item,
                        ((SourceRemapper.NeedsConfirmation) result).reason());
                baselines.insertItemSource(baseline, project, item, target, locator, NEEDS_CONFIRMATION, from);
                needsConfirmation++;
            }
        }
        if (remapped + needsConfirmation > 0) {
            log.info("baseline {} source remap: {} moved, {} need confirmation, {} carried", baseline, remapped,
                    needsConfirmation, carried);
        }
    }

    private static SourceRemapper.Result remap(SourceRemapper remapper, String locator) {
        try {
            return remapper.remap(locator == null ? null : JSON.readValue(locator, new TypeReference<Map<String, Object>>() { }));
        } catch (RuntimeException unreadable) {
            return new SourceRemapper.NeedsConfirmation("locator_unreadable");
        }
    }
}
