package com.example.sporty.match.integration;

import com.example.sporty.match.domain.MatchEntity;

import org.springframework.stereotype.Service;

/**
 * Validates dependencies before changing the shared entity. Caller owns authorization, match
 * locking, capacity checks and the transaction that saves the returned entity.
 */
@Service
public class MatchFacilityIntegration {
    private final MatchRemoteChecks checks;

    public MatchFacilityIntegration(MatchRemoteChecks checks) {
        this.checks = checks;
    }

    public MatchEntity create(Long userId, MatchEntity.Details details, Long facilityId) {
        var context = checks.checkCreation(userId, facilityId, details.startAt(), details.endAt());
        return new MatchEntity(details, context.facility().toEntitySnapshot());
    }

    public void update(MatchEntity match, MatchEntity.Details details, Long facilityId) {
        var snapshot = checks.checkFacility(facilityId, details.startAt(), details.endAt());
        match.update(details, snapshot.toEntitySnapshot());
    }
}
