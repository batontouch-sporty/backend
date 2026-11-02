package com.example.sporty.facility.facilities.domain.enums;

import java.util.Arrays;

import lombok.Getter;

/**
 * 자치구 코드(GANGNAM) ↔ 자치구명(강남구). 시설 데이터(location.region)는 자치구명으로 저장돼 있다.
 * user-service District와 같은 값이다.
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

    /** 알 수 없는 값이면 null */
    public static District fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(district -> district.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
