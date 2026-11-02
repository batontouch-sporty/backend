package com.example.sporty.ai.client.dto;

import java.util.List;

import com.example.sporty.ai.domain.enums.District;
import com.example.sporty.ai.domain.enums.Gender;
import com.example.sporty.ai.domain.enums.SportType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** user-service I1 /internal/users/{id} 응답 */
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternalUserDto {

    private Long id;
    private Gender gender;
    private String status;
    private District district;
    private List<SportType> sportTypes;
}
