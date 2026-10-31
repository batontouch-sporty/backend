package com.example.sporty.match.integration;
import java.time.LocalTime;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** I3 id is facility_db.service.id, never the public catalog's service_id. */
@FeignClient(name="facility-service",contextId="matchFacilityClient",url="${match.clients.facility.url:}")
public interface FacilityClient {
    @GetMapping("/internal/facilities/{id}") Facility get(@PathVariable("id")Long id);
    record Facility(Long id,String name,LocalTime startTime,LocalTime endTime,Boolean active,String url,String locationName,String region,Boolean isFree){}
}
