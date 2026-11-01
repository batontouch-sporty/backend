package com.example.sporty.match.participation;

import com.example.sporty.match.withdrawal.MemberLocks;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class ParticipationConfiguration {
    @Bean("participationMemberLocks")
    MemberLocks participationMemberLocks(DataSource dataSource) {
        return new MemberLocks(dataSource);
    }
}
