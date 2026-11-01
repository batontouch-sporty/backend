package com.example.sporty.match.integration;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/** Discovery is used by default; explicit URLs are only an environment override. */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {MemberClient.class, FacilityClient.class})
public class MatchFeignConfiguration {}
