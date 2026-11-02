import http from 'k6/http';
import { check, sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://gateway-service:8000';
const smoke = __ENV.SMOKE === '1';

export const options = {
  scenarios: {
    list: smoke
      ? { executor: 'shared-iterations', vus: 1, iterations: 1 }
      : { executor: 'constant-vus', vus: 100, duration: '60s' },
  },
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'] },
};

export default function () {
  const response = http.get(base + '/match-service/api/matches');
  check(response, {
    'A10 returns a nonempty match list': (r) => {
      if (r.status !== 200) return false;
      try {
        const rows = r.json();
        return Array.isArray(rows) && rows.length > 0 && Number.isInteger(rows[0].matchId);
      } catch (_) { return false; }
    },
  });
  if (!smoke) sleep(1);
}
