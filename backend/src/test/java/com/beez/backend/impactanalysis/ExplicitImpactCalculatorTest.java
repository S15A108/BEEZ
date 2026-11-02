package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExplicitImpactCalculatorTest {
    @Test void includesDirectAndIncomingConfirmedEdgesButNotKeywordTasksOrClosedTasks() {
        var snapshot=mock(ImpactContextQuery.Snapshot.class);
        var change=mock(ImpactContextQuery.Change.class);
        when(change.itemId()).thenReturn(10L);
        when(snapshot.changes()).thenReturn(List.of(change));
        var edge=mock(ImpactRelationReader.ConfirmedRelation.class);
        when(edge.fromItemId()).thenReturn(20L); when(edge.toItemId()).thenReturn(10L);
        when(snapshot.dependencies()).thenReturn(List.of(edge));
        var refs=List.of(ref(1,10L,"READY",null,70L),ref(2,20L,"READY",40L,null),
                ref(3,30L,"READY",null,null),ref(4,null,"READY",null,null),ref(5,10L,"COMPLETED",null,null),
                ref(6,10L,"CANCELLED",null,null),ref(1,10L,"READY",null,70L));
        when(snapshot.taskReferences()).thenReturn(refs);
        assertThat(new ExplicitImpactCalculator().calculate(snapshot)).containsExactly(
                Map.of("task_id","1","design_item_id","10","validation_id","70"),
                Map.of("task_id","2","design_item_id","10","member_id","40"));
    }
    private ImpactContextQuery.TaskReference ref(long task,Long item,String status,Long member,Long validation) {
        var ref=mock(ImpactContextQuery.TaskReference.class);
        when(ref.taskId()).thenReturn(task); when(ref.designItemId()).thenReturn(item);
        when(ref.status()).thenReturn(status); when(ref.assigneeMemberId()).thenReturn(member);
        when(ref.validationId()).thenReturn(validation); return ref;
    }
}
