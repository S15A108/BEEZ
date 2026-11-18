package com.beez.backend.project;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByIdAndArchivedAtIsNull(Long id);

    /**
     * 프로젝트 행을 잠그고 읽는다 (SELECT … FOR UPDATE). 수정·삭제가 같은 행을 동시에 저장하면
     * 뒤에 저장한 쪽이 앞의 archived_at 을 옛 값(null)으로 덮어 삭제를 되살릴 수 있어서, 둘 다 이걸로 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findForUpdate(@Param("id") long id);

    /**
     * 내가 활성 멤버인 삭제 안 된 프로젝트. 권한 조건을 페이지를 자르기 전에 건다 (00-common 3절).
     * 정렬은 id 내림차순 — IDENTITY 라 생성 순서와 같고, 작업 목록(TaskQueryService)과 같은 커서 방식이다.
     */
    @Query("""
            select new com.beez.backend.project.MyProjectRow(p, m.role)
              from ProjectMember m
              join Project p on p.id = m.projectId
             where m.accountId = :accountId
               and m.active = true
               and p.archivedAt is null
               and p.id < :beforeId
             order by p.id desc
            """)
    List<MyProjectRow> findMyProjects(@Param("accountId") long accountId, @Param("beforeId") long beforeId,
                                      Pageable pageable);
}
