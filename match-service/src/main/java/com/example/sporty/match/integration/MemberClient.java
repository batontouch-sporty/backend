package com.example.sporty.match.integration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.web.bind.annotation.*;

/** I1/I2 contracts owned by user-service; DTOs stay local to match-service. */
@FeignClient(name="user-service",contextId="matchMemberClient",url="${match.clients.user.url:}")
public interface MemberClient {
    @GetMapping("/internal/users/{id}") Member get(@PathVariable("id") Long id);
    @CollectionFormat(feign.CollectionFormat.CSV)
    @GetMapping("/internal/users") List<Profile> profiles(@RequestParam("ids") List<Long> ids);
    record Member(Long id,String gender,String status,String district,List<String> sportTypes){}
    record Profile(Long id,Long profileId,String nickname,String imageUrl){}
}
