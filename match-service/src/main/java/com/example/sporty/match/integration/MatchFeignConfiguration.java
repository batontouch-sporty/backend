package com.example.sporty.match.integration;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Discovery is used by default; explicit URLs are only an environment override. */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {MemberClient.class, FacilityClient.class})
public class MatchFeignConfiguration {
    @Bean
    MeterFilter feignHttpMeterNames() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getTag("clientName") == null) return id;
                return switch (id.getName()) {
                    case "http.client.requests" -> id.withName("sporty.feign.http.requests");
                    case "http.client.requests.active" ->
                            id.withName("sporty.feign.http.requests.active");
                    default -> id;
                };
            }
        };
    }
}
