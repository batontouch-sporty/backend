package com.example.sporty.match.deposit;
import com.example.sporty.match.integration.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticipationSuspensionTest {
    @Test void i1SuspensionBlocksNewActivityWhileExpiredSuspensionAllowsIt() {
        var members=mock(MemberClient.class);
        var checks=new MatchRemoteChecks(members,mock(FacilityClient.class));
        when(members.get(1L)).thenReturn(new MemberClient.Member(1L,"MALE","ACTIVE",null,List.of(),true,
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(7)));
        assertThatThrownBy(()->checks.activeMember(1L)).isInstanceOfSatisfying(MatchDependencyException.class,e->{
            assertThat(e.status()).isEqualTo(403); assertThat(e.code()).isEqualTo("PARTICIPATION_SUSPENDED");
        });
        when(members.get(1L)).thenReturn(new MemberClient.Member(1L,"MALE","ACTIVE",null,List.of(),false,
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusSeconds(1)));
        assertThat(checks.activeMember(1L).id()).isEqualTo(1L);
    }
}
