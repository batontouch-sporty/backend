package com.example.sporty.match;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/health-check")
    public String healthCheck() {
        return "match-service OK";
    }
}
