package com.example.sporty.match.withdrawal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
public class WithdrawalListener {
    private final ObjectMapper mapper;private final WithdrawalProcessor processor;
    public WithdrawalListener(ObjectMapper mapper,WithdrawalProcessor processor){this.mapper=mapper;this.processor=processor;}
    @KafkaListener(topics="user.withdrawal-requested.v1",groupId="match-service-withdrawal-v1",containerFactory="withdrawalKafkaFactory")
    public void receive(String payload)throws Exception{processor.handle(mapper.readValue(payload,WithdrawalRequest.class));}
}
