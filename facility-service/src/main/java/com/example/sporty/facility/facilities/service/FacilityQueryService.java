package com.example.sporty.facility.facilities.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.sporty.facility.commons.exception.InvalidSearchConditionException;
import com.example.sporty.facility.commons.exception.ServiceNotFoundException;
import com.example.sporty.facility.facilities.domain.dto.FacilityCandidateResponseDto;
import com.example.sporty.facility.facilities.domain.dto.InternalFacilityResponseDto;
import com.example.sporty.facility.facilities.domain.enums.District;
import com.example.sporty.facility.facilities.domain.enums.SportFacilityType;
import com.example.sporty.facility.facilities.domain.dto.ServiceDetailResponseDto;
import com.example.sporty.facility.facilities.domain.dto.ServiceRequestDto;
import com.example.sporty.facility.facilities.domain.dto.ServiceResponseDto;
import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;
import com.example.sporty.facility.facilities.repository.ServiceRepository;
import com.example.sporty.facility.facilities.specification.ServiceSpecification;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FacilityQueryService {

    private static final int CANDIDATE_MAX = 5;

    private final ServiceRepository serviceRepository;

    public List<ServiceResponseDto> getFacilities(
            ServiceRequestDto request
    ) {
        validateTimeRange(request);

        List<ServiceEntity> services = serviceRepository.findAll(
                ServiceSpecification.search(request)
        );

        return services.stream()
                .map(ServiceResponseDto::from)
                .toList();
    }

    // Path의 serviceId는 service 테이블 PK다.
    public ServiceDetailResponseDto getFacility(Long id) {
        ServiceEntity service = serviceRepository.findByIdAndActiveTrue(id)
                .orElseThrow(ServiceNotFoundException::new);

        return ServiceDetailResponseDto.from(service);
    }

    // I3: 비활성 시설도 200으로 주고 active: false로 알린다(2차 결정 21). 없는 id만 404.
    public InternalFacilityResponseDto getInternalFacility(Long id) {
        ServiceEntity service = serviceRepository.findWithLocationById(id)
                .orElseThrow(ServiceNotFoundException::new);

        return InternalFacilityResponseDto.from(service);
    }

    /**
     * I4: AI 초안의 시설 후보. 활성 시설만, 최대 5개(명세 결정).
     * 자치구·종목 중 아는 값만으로 거르고, 둘 다 없거나 알 수 없는 값이면 []를 준다(에러 없음).
     */
    public List<FacilityCandidateResponseDto> getCandidates(String districtCode, String sportCode) {
        District district = District.fromCode(districtCode);
        SportFacilityType sport = SportFacilityType.fromCode(sportCode);
        boolean hasSport = sport != null && !sport.getFacilityNames().isEmpty();

        // 종목을 줬는데 대응하는 시설 종류가 없으면(예: 러닝) 후보가 없다.
        if (sport != null && !hasSport) {
            return List.of();
        }

        PageRequest firstPage = PageRequest.of(0, CANDIDATE_MAX);
        List<ServiceEntity> services;
        if (district != null && hasSport) {
            services = serviceRepository.findByActiveTrueAndLocation_RegionAndLocation_FacilityNameInOrderByIdAsc(
                    district.getName(), sport.getFacilityNames(), firstPage);
        } else if (district != null) {
            services = serviceRepository.findByActiveTrueAndLocation_RegionOrderByIdAsc(district.getName(), firstPage);
        } else if (hasSport) {
            services = serviceRepository.findByActiveTrueAndLocation_FacilityNameInOrderByIdAsc(
                    sport.getFacilityNames(), firstPage);
        } else {
            return List.of();
        }

        return services.stream()
                .map(FacilityCandidateResponseDto::from)
                .toList();
    }

    private void validateTimeRange(ServiceRequestDto request) {
        if (request.getStartTime() != null
                && request.getEndTime() != null
                && request.getStartTime().isAfter(request.getEndTime())) {
            throw new InvalidSearchConditionException(
                    "시작시간은 종료시간보다 늦을 수 없습니다."
            );
        }
    }
}
