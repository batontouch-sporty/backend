package com.example.sporty.facility.facilities.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.example.sporty.facility.facilities.domain.entity.ServiceEntity;

public interface ServiceRepository
        extends JpaRepository<ServiceEntity, Long>,
                JpaSpecificationExecutor<ServiceEntity> {

    Optional<ServiceEntity>
        findFirstByActiveTrueAndLocation_FacilityNameOrderByIdAsc(
            String facilityName
        );


    Optional<ServiceEntity> findByServiceId(String serviceId);

    @EntityGraph(attributePaths = "location")
    Optional<ServiceEntity> findByIdAndActiveTrue(Long id);

    List<ServiceEntity> findAllByActiveTrue();

    // I4 시설 후보: 자치구·시설 종류가 맞는 활성 시설
    @EntityGraph(attributePaths = "location")
    List<ServiceEntity> findByActiveTrueAndLocation_RegionAndLocation_FacilityNameInOrderByIdAsc(
            String region, Collection<String> facilityNames, Pageable pageable);

    @EntityGraph(attributePaths = "location")
    List<ServiceEntity> findByActiveTrueAndLocation_RegionOrderByIdAsc(String region, Pageable pageable);

    @EntityGraph(attributePaths = "location")
    List<ServiceEntity> findByActiveTrueAndLocation_FacilityNameInOrderByIdAsc(
            Collection<String> facilityNames, Pageable pageable);

    // I3 내부 조회용. 비활성 시설도 찾는다.
    @EntityGraph(attributePaths = "location")
    Optional<ServiceEntity> findWithLocationById(Long id);

    @EntityGraph(attributePaths = "location")
    List<ServiceEntity> findAll(
            Specification<ServiceEntity> specification
    );
}
