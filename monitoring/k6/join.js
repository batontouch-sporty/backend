import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const base = __ENV.BASE_URL || 'http://gateway-service:8000';
const fixture = JSON.parse(open('./.run/participants.json'));
const members = new SharedArray('members', () => fixture.members);
const matchId = fixture.matchId;
const timeout = Number(__ENV.SETTLEMENT_TIMEOUT_SECONDS || 360);
if (!Number.isInteger(matchId) || matchId < 1 || !Number.isInteger(fixture.capacity) || fixture.capacity < 2 ||
    members.length < 1 || members.length > 100 || !Number.isInteger(timeout) || timeout < 1 || timeout > 600 ||
    new Set(members.map(m => m.userId)).size !== members.length ||
    new Set(members.map(m => m.token)).size !== members.length) {
  throw new Error('Distinct funded accounts, a fresh match and a 1..600 second deadline are required');
}
const attempts = new Counter('join_attempts');
const accepted = new Counter('join_accepted');
const confirmed = new Counter('join_confirmed');
const closed = new Counter('join_capacity_rejected');
const errors = new Counter('join_unexpected');
const refunds = new Counter('join_refund_completed');
const acceptanceTime = new Trend('join_acceptance_ms', true);
const confirmationTime = new Trend('join_confirmation_ms', true);
const settlementTime = new Trend('join_settlement_ms', true);
const expected = Math.min(members.length, fixture.capacity - 1);
const capacityCodes = ['MATCH_RECRUITMENT_CLOSED', 'MATCH_FULL'];

export const options = {
  scenarios: { join: { executor: 'per-vu-iterations', vus: members.length, iterations: 1, maxDuration: `${timeout + 45}s` } },
  thresholds: {
    join_attempts: [`count==${members.length}`],
    join_confirmed: [`count==${expected}`],
    join_capacity_rejected: [`count==${members.length - expected}`],
    join_unexpected: ['count==0'],
  },
};

/** Refuse a reused or unconfirmed fixture before any participant sends a POST. */
export function setup() {
  const r = http.get(`${base}/match-service/api/matches/${matchId}`, { tags: { operation: 'fixture_check' } });
  const data = json(r);
  if (r.status !== 200 || data?.matchId !== matchId || data.status !== 'RECRUITING' ||
      data.maxParticipant !== fixture.capacity || data.currentParticipantCount !== 1) {
    throw new Error('Prepare a fresh match with exactly one confirmed owner before join');
  }
}

/** Decode malformed responses as failures without logging bodies or authentication headers. */
function json(response) {
  try { return response.json(); } catch (_) { return null; }
}

/** Poll the original request until confirmation or final rejection and any required refund. */
function settle(requestId, member, deadline) {
  while (Date.now() < deadline) {
    sleep(1);
    const r = http.get(`${base}/match-service/api/matches/requests/${encodeURIComponent(requestId)}`, {
      headers: { Authorization: `Bearer ${member.token}` }, timeout: '15s',
      tags: { operation: 'request_status', name: 'GET participation request' },
    });
    const result = json(r);
    if (r.status !== 200 || result?.requestId !== requestId || result?.matchId !== matchId ||
        !['PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED'].includes(result.status)) return null;
    if (result.status === 'PENDING') continue;
    if (result.status === 'CONFIRMED') return result.refundStatus === 'NOT_REQUIRED' ? result : null;
    if (result.refundStatus === 'PENDING') continue;
    return ['NOT_REQUIRED', 'COMPLETED'].includes(result.refundStatus) ? result : null;
  }
  return null;
}

/** Send one join per account; count acceptance separately from the eventual business result. */
export default function () {
  const member = members[exec.vu.idInTest - 1];
  const start = Date.now();
  attempts.add(1);
  let wasAccepted = false, success = false, capacity = false, refunded = false;
  const r = http.post(`${base}/match-service/api/matches/${matchId}/participants`, null, {
    headers: { Authorization: `Bearer ${member.token}` }, timeout: '15s',
    tags: { operation: 'join_acceptance', name: 'POST participation' },
    responseCallback: http.expectedStatuses(202, 400),
  });
  acceptanceTime.add(r.timings.duration);
  const data = json(r);
  if (r.status === 400 && capacityCodes.includes(data?.code)) {
    capacity = true;
  } else if (r.status === 202 && data?.matchId === matchId && data.status === 'PENDING' &&
             typeof data.requestId === 'string' && /^[0-9a-f-]{36}$/i.test(data.requestId)) {
    wasAccepted = true;
    const result = settle(data.requestId, member, start + timeout * 1000);
    if (result) {
      success = result.status === 'CONFIRMED';
      capacity = ['FAILED', 'CANCELLED'].includes(result.status) && capacityCodes.includes(result.failureCode);
      refunded = result.refundStatus === 'COMPLETED';
      settlementTime.add(Date.now() - start);
      if (success) confirmationTime.add(Date.now() - start);
    }
  }
  accepted.add(wasAccepted ? 1 : 0);
  confirmed.add(success ? 1 : 0);
  closed.add(capacity ? 1 : 0);
  refunds.add(refunded ? 1 : 0);
  errors.add(success || capacity ? 0 : 1);
  if (!success && !capacity) console.warn(`Unresolved/unexpected join: user=${member.userId} HTTP=${r.status}`);
}

/** Bind structured k6 metrics to this fixture so an old successful run cannot pass verification. */
export function handleSummary(data) {
  const counts = Object.fromEntries(['join_attempts', 'join_accepted', 'join_confirmed',
    'join_capacity_rejected', 'join_unexpected', 'join_refund_completed'].map(k => [k, data.metrics[k]?.values.count || 0]));
  return {
    '/results/join-results.json': JSON.stringify({ runId: fixture.runId, matchId, ...data }, null, 2),
    stdout: JSON.stringify({ ...counts, acceptance_ms: data.metrics.join_acceptance_ms?.values, confirmation_ms: data.metrics.join_confirmation_ms?.values,
      settlement_ms: data.metrics.join_settlement_ms?.values }, null, 2) + '\n',
  };
}
