package com.example.sporty.facility.facilities.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 종목(SportType 코드) → 서울시 시설 소분류명(MINCLASSNM) 매핑. I4 시설 후보 조회에서 쓴다.
 * 공통 모듈 없이 서비스마다 복사한다(구현계획 v2). 1차 SportType.fromFacilityType과 같은 기준이다.
 * 러닝은 대응하는 시설 종류가 없어 후보가 없다.
 */
public enum SportFacilityType {
    SOCCER("축구장"),
    FUTSAL("풋살장"),
    BASKETBALL("농구장"),
    BASEBALL("야구장"),
    TENNIS("테니스장"),
    BADMINTON("배드민턴장"),
    TABLE_TENNIS("탁구장"),
    VOLLEYBALL("배구장"),
    SWIMMING("수영장"),
    RUNNING();

    private final List<String> facilityNames;

    SportFacilityType(String... facilityNames) {
        this.facilityNames = List.of(facilityNames);
    }

    public List<String> getFacilityNames() {
        return facilityNames;
    }

    /** 알 수 없는 값이면 null */
    public static SportFacilityType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
