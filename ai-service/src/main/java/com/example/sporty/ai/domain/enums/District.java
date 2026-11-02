package com.example.sporty.ai.domain.enums;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import lombok.Getter;

/**
 * user-service District와 같은 값.
 * 회원 정보(I1)는 코드(GANGNAM), 매치는 자치구명(강남구)을 써서 이 enum으로 바꾼다.
 */
@Getter
public enum District {
    GANGNAM("강남구"),
    GANGDONG("강동구"),
    GANGBUK("강북구"),
    GANGSEO("강서구"),
    GWANAK("관악구"),
    GWANGJIN("광진구"),
    GURO("구로구"),
    GEUMCHEON("금천구"),
    NOWON("노원구"),
    DOBONG("도봉구"),
    DONGDAEMUN("동대문구"),
    DONGJAK("동작구"),
    MAPO("마포구"),
    SEODAEMUN("서대문구"),
    SEOCHO("서초구"),
    SEONGDONG("성동구"),
    SEONGBUK("성북구"),
    SONGPA("송파구"),
    YANGCHEON("양천구"),
    YEONGDEUNGPO("영등포구"),
    YONGSAN("용산구"),
    EUNPYEONG("은평구"),
    JONGNO("종로구"),
    JUNG("중구"),
    JUNGRANG("중랑구");

    private final String name;

    District(String name) {
        this.name = name;
    }

    /** 이름이 긴 자치구부터 (예: "중랑구"를 "중구"보다 먼저 찾도록) */
    public static List<District> longestNameFirst() {
        return Arrays.stream(values())
                .sorted(Comparator.comparingInt((District d) -> d.name.length()).reversed())
                .toList();
    }

    /** 자치구명(강남구) → 코드(GANGNAM). 모르는 이름이면 null */
    public static District fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return Arrays.stream(values())
                .filter(district -> district.name.equals(trimmed))
                .findFirst()
                .orElse(null);
    }

    /** "강남구"뿐 아니라 "강남"으로도 찾는다. "중구"는 "중"이 너무 흔해 전체 이름만 본다. */
    public String shortName() {
        String withoutSuffix = name.substring(0, name.length() - 1);
        return withoutSuffix.length() >= 2 ? withoutSuffix : name;
    }
}
