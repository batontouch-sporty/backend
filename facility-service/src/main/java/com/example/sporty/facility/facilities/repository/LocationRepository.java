package com.example.sporty.facility.facilities.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.sporty.facility.facilities.domain.entity.LocationEntity;

public interface LocationRepository extends JpaRepository<LocationEntity, Long> {

    Optional<LocationEntity> findByRegionAndNameAndFacilityName(
            String region,
            String name,
            String facilityName
    );
}
