package com.beez.backend.ontology;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Authentication and malformed inputs must fail before any database access. */
class DesignDependencyServiceTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ProjectAccessChecker access = mock(ProjectAccessChecker.class);
    private final DesignDependencyService service = new DesignDependencyService(jdbc, access);
    private final CallerContext caller = CallerContext.human(7L);

    @Test
    void permissionFailureDoesNotReadOrWriteRelations() {
        when(access.requireHumanLeader(caller, 1L))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "forbidden", "팀장만 가능합니다"));
        assertThatThrownBy(() -> service.confirm(caller, 1L, 2L, 3L, 4L, "사유"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsSelfDependencyBeforeDatabaseAccess() {
        when(access.requireHumanLeader(caller, 1L)).thenReturn(mock(ProjectActor.class));
        assertThatThrownBy(() -> service.confirm(caller, 1L, 2L, 3L, 3L, "사유"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("validation_failed"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void requiresNonBlankReasonBeforeDatabaseAccess() {
        when(access.requireHumanLeader(caller, 1L)).thenReturn(mock(ProjectActor.class));
        assertThatThrownBy(() -> service.confirm(caller, 1L, 2L, 3L, 4L, "  "))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }
}
