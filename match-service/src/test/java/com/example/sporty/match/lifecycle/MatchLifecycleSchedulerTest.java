package com.example.sporty.match.lifecycle;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

class MatchLifecycleSchedulerTest {
    @Test
    void failingFirstPageDoesNotStarveLaterMatches() {
        var lifecycle = mock(MatchLifecycle.class);
        when(lifecycle.candidates(0)).thenReturn(LongStream.rangeClosed(1, 100).boxed().toList());
        when(lifecycle.candidates(100)).thenReturn(List.of(101L));
        when(lifecycle.candidates(101)).thenReturn(List.of());
        doThrow(new IllegalStateException("broken")).when(lifecycle).advance(longThat(id -> id <= 100));

        new MatchLifecycleScheduler(lifecycle).scan();

        verify(lifecycle).advance(101L);
        verify(lifecycle, times(101)).advance(anyLong());
    }
}
