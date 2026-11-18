package com.beez.backend.common.api;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.OptimisticLockException;
import java.lang.reflect.Method;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void logsHibernateConstraintWithoutSqlOrValues(CapturedOutput output) {
        var sql = new SQLException("비공개 문서 본문", "23503");
        var cause = new ConstraintViolationException("비공개 문서 본문", sql, "INSERT 비공개 SQL", "fk_task_project");
        var response = handler.handleIntegrity(new DataIntegrityViolationException("비공개 문서 본문", cause));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(output.getOut()).contains("constraint_name=fk_task_project", "sql_state=23503")
                .doesNotContain("비공개 문서 본문", "비공개 SQL");
    }

    @Test void logsPostgresConstraintFromJdbcBatchException(CapturedOutput output) {
        var server = new ServerErrorMessage("SERROR\0C23505\0M비공개 오류 메시지\0D비공개 데이터 값\0nux_document_project_type\0\0");
        var batch = new BatchUpdateException();
        batch.setNextException(new PSQLException(server));
        handler.handleIntegrity(new DataIntegrityViolationException("비공개 상위 예외 메시지", batch));
        assertThat(output.getOut()).contains("constraint_name=ux_document_project_type", "sql_state=23505")
                .doesNotContain("비공개 오류 메시지", "비공개 데이터 값", "비공개 상위 예외 메시지");
    }

    @Test void handlesMissingConstraintMetadata(CapturedOutput output) {
        handler.handleIntegrity(new DataIntegrityViolationException("비공개 오류 메시지", new SQLException("비공개 데이터 값", "23502")));
        assertThat(output.getOut()).contains("constraint_name=unknown", "sql_state=23502").doesNotContain("비공개 데이터 값");
    }

    @Test void rawVersionConflicts_areStillRevisionConflict409() {
        // -300 방어(-308 에서 유지): @Repository 밖 auto-flush 에서 난 원형 버전 충돌도 409 여야 한다.
        // 입구 경로는 이제 변환돼 와서 통합 시험으로는 이 매핑이 빠져도 모른다 — 빠지면 handleUnexpected(500)로 간다
        var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
        for (Exception raw : new Exception[] {new OptimisticLockException("충돌"), new StaleObjectStateException("Task", 1L)}) {
            assertThat(resolver.resolveMethod(raw)).as(raw.getClass().getSimpleName())
                    .isNotNull().extracting(Method::getName).isEqualTo("handleOptimisticLock");
        }
        var response = handler.handleOptimisticLock(new OptimisticLockException("충돌"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().error().code()).isEqualTo("revision_conflict");
    }
}
