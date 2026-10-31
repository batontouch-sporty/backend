package com.example.sporty.facility.facilities.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.sporty.facility.facilities.domain.entity.LocationEntity;
import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;
import com.example.sporty.facility.facilities.repository.LocationRepository;
import com.example.sporty.facility.facilities.repository.ServiceRepository;
import com.example.sporty.facility.openapi.SeoulFacilityApiClient;
import com.example.sporty.facility.openapi.domain.dto.SeoulFacilityResponseDto.Row;

import lombok.RequiredArgsConstructor;

/**
 * 서울시 체육시설 전체 목록을 로컬 DB와 동기화한다.
 */
@Service
@RequiredArgsConstructor
public class FacilitySyncService {

    private static final Logger log =
            LoggerFactory.getLogger(FacilitySyncService.class);
    private static final DateTimeFormatter SEOUL_DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                    .optionalEnd()
                    .toFormatter();

    private final SeoulFacilityApiClient apiClient;
    private final LocationRepository locationRepository;
    private final ServiceRepository serviceRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 공공데이터 API 키가 있을 때만 동기화할 수 있다.
     */
    public boolean isSyncAvailable() {
        return apiClient.isConfigured();
    }

    public SyncResult syncAll() {
        if (!apiClient.isConfigured()) {
            log.warn("공공데이터 API 키(PUBLIC_DATA_API_KEY)가 없어 체육시설 동기화를 건너뜁니다.");
            return SyncResult.skipped();
        }

        List<Row> rows = apiClient.fetchAll();
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "서울시 체육시설 데이터가 비어 있어 동기화를 중단합니다."
            );
        }

        SyncResult result = transactionTemplate.execute(
                status -> synchronize(rows)
        );

        if (result == null) {
            throw new IllegalStateException("체육시설 DB 동기화에 실패했습니다.");
        }

        log.info(
                "체육시설 동기화 완료: 조회 {}, 신규 {}, 갱신 {}, 비활성화 {}, 제외 {}",
                result.fetchedCount(),
                result.createdCount(),
                result.updatedCount(),
                result.deactivatedCount(),
                result.skippedCount()
        );
        return result;
    }

    private SyncResult synchronize(List<Row> rows) {
        Map<LocationKey, LocationEntity> locationCache = loadLocationCache();
        Map<String, ServiceEntity> serviceCache = loadServiceCache();
        Set<String> seenServiceIds = new HashSet<>();

        int createdCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;
        boolean hasRowWithoutServiceId = false;

        for (Row row : rows) {
            String serviceId = trimToNull(row.getSvcId());

            // SVCID가 없으면 어떤 시설인지 알 수 없다.
            // 이 행이 기존 시설일 수도 있으므로 이번 동기화에서는 비활성화를 하지 않는다.
            if (serviceId == null) {
                skippedCount++;
                hasRowWithoutServiceId = true;
                log.warn("SVCID가 없는 서울시 체육시설 데이터를 제외합니다.");
                continue;
            }

            // 다른 필드가 비어 있어도 응답에 포함된 시설이므로 먼저 수신 목록에 기록한다.
            // 그래야 불완전한 행 때문에 기존 활성 시설이 사라진 시설로 판단되지 않는다.
            seenServiceIds.add(serviceId);

            String serviceName = trimToNull(row.getSvcNm());
            String region = trimToNull(row.getAreaNm());
            String placeName = trimToNull(row.getPlaceNm());
            String facilityName = trimToNull(row.getMinClassNm());

            if (serviceName == null
                    || region == null
                    || placeName == null
                    || facilityName == null) {
                skippedCount++;
                log.warn("필수값이 없는 서울시 체육시설 데이터는 기존 값을 유지합니다: {}", serviceId);
                continue;
            }

            LocationEntity location = findOrCreateLocation(
                    row,
                    region,
                    placeName,
                    facilityName,
                    locationCache
            );

            ServiceEntity service = serviceCache.get(serviceId);
            if (service == null) {
                service = createService(
                        row,
                        location,
                        serviceId,
                        serviceName
                );
                serviceRepository.save(service);
                serviceCache.put(serviceId, service);
                createdCount++;
            } else {
                service.update(
                        location,
                        serviceName,
                        trimToNull(row.getSvcStatNm()),
                        parseTime(row.getVMin(), false),
                        parseTime(row.getVMax(), true),
                        parseIsFree(row.getPayAtNm()),
                        trimToNull(row.getSvcUrl()),
                        parseDateTime(row.getRcptEndDt())
                );
                updatedCount++;
            }
        }

        int deactivatedCount = 0;
        if (hasRowWithoutServiceId) {
            log.warn("SVCID가 없는 행이 있어 이번 동기화에서는 시설 비활성화를 건너뜁니다.");
        } else {
            for (ServiceEntity service : serviceCache.values()) {
                if (service.isActive()
                        && !seenServiceIds.contains(service.getServiceId())) {
                    service.deactivate();
                    deactivatedCount++;
                }
            }
        }

        return new SyncResult(
                rows.size(),
                createdCount,
                updatedCount,
                deactivatedCount,
                skippedCount
        );
    }

    private LocationEntity findOrCreateLocation(
            Row row,
            String region,
            String placeName,
            String facilityName,
            Map<LocationKey, LocationEntity> locationCache
    ) {
        LocationKey key = new LocationKey(region, placeName, facilityName);
        String contact = trimToNull(row.getTelNo());
        BigDecimal latitude = parseDecimal(row.getY());
        BigDecimal longitude = parseDecimal(row.getX());

        LocationEntity location = locationCache.get(key);
        if (location == null) {
            LocationKey legacyKey = new LocationKey(region, placeName, null);
            location = locationCache.remove(legacyKey);
        }

        if (location == null) {
            location = LocationEntity.builder()
                    .region(region)
                    .name(placeName)
                    .facilityName(facilityName)
                    .contact(contact)
                    .latitude(latitude)
                    .longitude(longitude)
                    .build();
            locationRepository.save(location);
        } else {
            location.update(
                    facilityName,
                    contact,
                    latitude,
                    longitude
            );
        }
        locationCache.put(key, location);

        return location;
    }

    private ServiceEntity createService(
            Row row,
            LocationEntity location,
            String serviceId,
            String serviceName
    ) {
        return ServiceEntity.builder()
                .location(location)
                .serviceId(serviceId)
                .name(serviceName)
                .status(trimToNull(row.getSvcStatNm()))
                .startTime(parseTime(row.getVMin(), false))
                .endTime(parseTime(row.getVMax(), true))
                .isFree(parseIsFree(row.getPayAtNm()))
                .url(trimToNull(row.getSvcUrl()))
                .reservationDeadlineAt(parseDateTime(row.getRcptEndDt()))
                .build();
    }

    private Map<LocationKey, LocationEntity> loadLocationCache() {
        Map<LocationKey, LocationEntity> cache = new HashMap<>();
        for (LocationEntity location : locationRepository.findAll()) {
            cache.putIfAbsent(
                    new LocationKey(
                            location.getRegion(),
                            location.getName(),
                            location.getFacilityName()
                    ),
                    location
            );
        }
        return cache;
    }

    private Map<String, ServiceEntity> loadServiceCache() {
        Map<String, ServiceEntity> cache = new HashMap<>();
        for (ServiceEntity service : serviceRepository.findAll()) {
            cache.put(service.getServiceId(), service);
        }
        return cache;
    }

    private BigDecimal parseDecimal(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            log.warn("좌표 변환에 실패했습니다: {}", normalized);
            return null;
        }
    }

    private LocalTime parseTime(String value, boolean endTime) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
    
        if ("24:00".equals(normalized)) {
            return endTime
                    ? LocalTime.of(23, 59, 59)
                    : LocalTime.MIDNIGHT;
        }
    
        try {
            return LocalTime.parse(normalized);
        } catch (DateTimeParseException exception) {
            log.warn("운영시간 변환에 실패했습니다: {}", normalized);
            return null;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        try {
            return LocalDateTime.parse(
                    normalized,
                    SEOUL_DATE_TIME_FORMATTER
            );
        } catch (DateTimeParseException exception) {
            log.warn("예약 접수 마감 일시 변환에 실패했습니다: {}", normalized);
            return null;
        }
    }

    private Boolean parseIsFree(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        if (normalized.startsWith("무료")) {
            return true;
        }
        if (normalized.startsWith("유료")) {
            return false;
        }

        log.warn("유무료 여부를 해석할 수 없습니다: {}", normalized);
        return null;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record LocationKey(
            String region,
            String name,
            String facilityName
    ) {
    }

    public record SyncResult(
            int fetchedCount,
            int createdCount,
            int updatedCount,
            int deactivatedCount,
            int skippedCount
    ) {
        static SyncResult skipped() {
            return new SyncResult(0, 0, 0, 0, 0);
        }
    }
}
