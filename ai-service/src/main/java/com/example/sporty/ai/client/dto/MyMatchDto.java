package com.example.sporty.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** match-service A16 내 매치 목록 항목. 추천에서 내가 만들었거나 참가한 매치를 빼는 데만 쓴다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MyMatchDto {

    private Long matchId;
}
