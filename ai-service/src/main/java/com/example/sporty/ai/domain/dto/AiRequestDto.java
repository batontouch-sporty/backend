package com.example.sporty.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A22 검색·A23 초안 공통 요청: { "prompt": "..." } (1자 이상 500자 이하) */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiRequestDto {

    @NotBlank(message = "요청 내용을 입력해주세요.")
    @Size(max = 500, message = "요청은 500자 이하로 입력해주세요.")
    private String prompt;
}
