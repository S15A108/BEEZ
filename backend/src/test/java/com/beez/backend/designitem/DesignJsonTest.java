package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.designitem.support.DesignJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignJsonTest {
    @Test void hashIgnoresNestedObjectMemberOrderButPreservesArrayOrderAndText() {
        var a = new LinkedHashMap<String, Object>();
        a.put("description", " 로그인 "); a.put("nested", Map.of("b", 2, "a", 1));
        var b = new LinkedHashMap<String, Object>();
        b.put("nested", Map.of("a", 1, "b", 2)); b.put("description", " 로그인 ");
        assertThat(DesignJson.hash(a)).isEqualTo(DesignJson.hash(b));
        assertThat(DesignJson.hash(List.of(1, 2))).isNotEqualTo(DesignJson.hash(List.of(2, 1)));
        assertThat(DesignJson.hash(" 로그인 ")).isNotEqualTo(DesignJson.hash("로그인"));
    }
    @Test void rejectsExcessivelyNestedInputWithoutMutatingCallerObjects() {
        Object value = "text";
        for (int i = 0; i < 34; i++) value = List.of(value);
        Object deep = value;
        assertThatThrownBy(() -> DesignJson.canonical(deep)).isInstanceOf(ApiException.class);
        var original = new LinkedHashMap<String, Object>();
        original.put("z", 1); original.put("a", 2);
        DesignJson.hash(original);
        assertThat(original.keySet()).containsExactly("z", "a");
    }
}
