"""Regression checks for false passes in the load-test evidence verifier (no services needed)."""
import copy
import unittest
from fixture import validate


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = dict(runId="run", matchId=1, capacity=2, owner=dict(userId=1, baselinePoints=20),
                            members=[dict(userId=2), dict(userId=3)])
        self.db = dict(match=dict(matchId=1, capacity=2, status="CLOSED"),
                       participants=[], requests=[], history=[], users=[])
        for uid, status, refund, points in [(1, "CONFIRMED", "NOT_REQUIRED", 20),
                                            (2, "CONFIRMED", "NOT_REQUIRED", 40),
                                            (3, "CANCELLED", "COMPLETED", 50)]:
            rid = str(uid)
            self.db["participants"].append(dict(userId=uid, requestId=rid,
                                                role="OWNER" if uid == 1 else "PARTICIPANT", status=status))
            self.db["requests"].append(dict(userId=uid, requestId=rid, matchId=1, status=status,
                                            refundStatus=refund, failureCode="MATCH_FULL" if uid == 3 else None))
            self.db["users"].append(dict(userId=uid, points=points, status="ACTIVE", suspended=False))
            self.db["history"].append(dict(userId=uid, requestId=rid, reason="DEPOSIT_CHARGED",
                                           delta=-10, before=30 if uid == 1 else 50, after=20 if uid == 1 else 40))
        self.db["history"].append(dict(userId=3, requestId="3", reason="DEPOSIT_RETURNED",
                                       delta=10, before=40, after=50))
        self.result = dict(runId="run", matchId=1, metrics={k: {"values": {"count": v}} for k, v in dict(
            join_attempts=2, join_accepted=2, join_confirmed=1, join_capacity_rejected=1,
            join_unexpected=0, join_refund_completed=1).items()})

    def test_cancelled_row_is_not_counted_as_confirmed(self):
        self.assertEqual(validate(self.fixture, self.db, self.result)["dbConfirmed"], 2)

    def test_immediate_capacity_rejection_needs_no_request_or_charge(self):
        self.db["participants"].pop()
        self.db["requests"].pop()
        self.db["history"] = [h for h in self.db["history"] if h["userId"] != 3]
        self.result["metrics"]["join_accepted"]["values"]["count"] = 1
        self.result["metrics"]["join_refund_completed"]["values"]["count"] = 0
        self.assertTrue(validate(self.fixture, self.db, self.result)["verified"])

    def test_old_successful_summary_cannot_validate_new_fixture(self):
        self.result["runId"] = "previous"
        with self.assertRaisesRegex(RuntimeError, "Stale"):
            validate(self.fixture, self.db, self.result)

    def test_over_capacity_is_rejected(self):
        self.db["participants"][2]["status"] = "CONFIRMED"
        with self.assertRaisesRegex(RuntimeError, "capacity"):
            validate(self.fixture, self.db, self.result)

    def test_pending_refund_cannot_pass(self):
        self.db["requests"][2]["refundStatus"] = "PENDING"
        with self.assertRaisesRegex(RuntimeError, "Unfinished refund"):
            validate(self.fixture, self.db, self.result)

    def test_refund_event_without_credit_history_cannot_pass(self):
        self.db["history"].pop()
        with self.assertRaisesRegex(RuntimeError, "refund history"):
            validate(self.fixture, self.db, self.result)

    def test_duplicate_charge_cannot_pass(self):
        self.db["history"].append(copy.deepcopy(self.db["history"][1]))
        with self.assertRaisesRegex(RuntimeError, "exactly one charge"):
            validate(self.fixture, self.db, self.result)

    def test_balance_must_match_settlement(self):
        self.db["users"][2]["points"] = 40
        with self.assertRaisesRegex(RuntimeError, "Unexpected points"):
            validate(self.fixture, self.db, self.result)

    def test_poll_failure_cannot_pass_even_when_db_confirmed(self):
        self.result["metrics"]["join_unexpected"]["values"]["count"] = 1
        with self.assertRaisesRegex(RuntimeError, "Missing attempts"):
            validate(self.fixture, self.db, self.result)


if __name__ == "__main__":
    unittest.main()
