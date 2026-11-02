import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const base = __ENV.BASE_URL || 'http://gateway-service:8000';
const fixture = JSON.parse(open('./.run/participants.json'));
const members = new SharedArray('members', () => fixture.members);
const matchId = fixture.matchId;
if (!Number.isInteger(matchId) || matchId < 1 || members.length !== 100 ||
    new Set(members.map((m) => m.userId)).size !== 100 ||
    new Set(members.map((m) => m.token)).size !== 100) {
  throw new Error('100 distinct test accounts and an unused match are required');
}
const joined = new Counter('join_created');
const closed = new Counter('join_capacity_rejected');
const errors = new Counter('join_unexpected');

export const options = {
  scenarios: { join: { executor: 'per-vu-iterations', vus: 100, iterations: 1, maxDuration: '90s' } },
  thresholds: {
    http_reqs: ['count==100'], join_created: ['count==9'],
    join_capacity_rejected: ['count==91'], join_unexpected: ['count==0'],
  },
};

export default function () {
  const member = members[exec.vu.idInTest - 1];
  const r = http.post(base + '/match-service/api/matches/' + matchId + '/participants', null, {
    headers: { Authorization: 'Bearer ' + member.token },
    responseCallback: http.expectedStatuses(201, 400),
  });
  let data;
  try { data = r.json(); } catch (_) { data = {}; }
  const success = r.status === 201 && data?.userId === member.userId && data?.matchId === matchId;
  const full = r.status === 400 && ['MATCH_RECRUITMENT_CLOSED', 'MATCH_FULL'].includes(data?.code);
  joined.add(success ? 1 : 0);
  closed.add(full ? 1 : 0);
  errors.add(success || full ? 0 : 1);
  if (!success && !full) console.warn('Unexpected join response', r.status, data?.code || 'unknown');
}
