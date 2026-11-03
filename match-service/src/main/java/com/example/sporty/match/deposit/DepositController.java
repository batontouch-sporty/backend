package com.example.sporty.match.deposit;
import com.example.sporty.match.participation.ParticipationProblem;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/matches/requests")
public class DepositController {
    private final DepositSaga saga;
    public DepositController(DepositSaga saga) { this.saga=saga; }
    @GetMapping("/{requestId}")
    public DepositSaga.Status status(@PathVariable String requestId,
            @RequestHeader(value="X-User-Id",required=false) String identity) {
        long user;
        try { user=Long.parseLong(identity); if(user<=0) throw new NumberFormatException(); }
        catch(RuntimeException e) { throw new ParticipationProblem(401,"UNAUTHORIZED","로그인이 필요합니다."); }
        return saga.status(requestId,user);
    }
}
