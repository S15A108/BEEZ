package com.beez.backend.bee.dto;

/** BEE-A04 응답. 한가하면 activity=IDLE 이고 나머지는 내려가지 않는다 (전역 non_null) */
public record BeeStatusResponse(String activity, String targetType, String targetId, String label) {

    public static BeeStatusResponse idle() {
        return new BeeStatusResponse("IDLE", null, null, null);
    }
}
