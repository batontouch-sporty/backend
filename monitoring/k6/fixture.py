"""Prepare and verify TC-OBS-003 against the isolated R10/Saga test stack."""
import argparse
import json
import secrets
import subprocess
import time
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.request import Request, urlopen

PROJECT = "sporty-k6-90"
BASE = "http://127.0.0.1:18090"
RUN = Path(__file__).parent / ".run"
CAPACITY = 10
CAPACITY_ERRORS = {"MATCH_FULL", "MATCH_RECRUITMENT_CLOSED"}


def require(condition, message):
    """Fail verification even when Python is invoked with optimizations enabled."""
    if not condition:
        raise RuntimeError(message)


def sql(statement):
    """Run fixture SQL only in the dedicated project's MariaDB container."""
    result = subprocess.run(
        ["docker", "exec", "-i", PROJECT + "-mariadb-1", "sh", "-c",
         'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb -uroot -BNr --default-character-set=utf8mb4'],
        input=statement, text=True, encoding="utf-8", stdout=subprocess.PIPE, check=True,
    )
    return result.stdout.strip()


def query_json(statement):
    """Decode one JSON object per SQL result row without exposing credentials."""
    return [json.loads(line) for line in sql(statement).splitlines() if line]


def request(path, data=None, token=None):
    """Call the Gateway; return HTTP status and parsed JSON for strict contract checks."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(BASE + path, None if data is None else json.dumps(data).encode(), headers)
    with urlopen(req, timeout=15) as response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def wait_created(accepted, token):
    """Wait up to six minutes for an accepted creation; never treat PENDING as success."""
    deadline = time.monotonic() + 360
    while time.monotonic() < deadline:
        _, result = request("/match-service/api/matches/requests/" + accepted["requestId"], token=token)
        require(result.get("requestId") == accepted["requestId"]
                and result.get("matchId") == accepted["matchId"], "Mismatched creation result")
        if result["status"] == "CONFIRMED":
            return
        require(result["status"] == "PENDING", f"Creation failed: {result.get('failureCode')}")
        time.sleep(1)
    raise RuntimeError("Creation did not confirm within 360 seconds")


def read_db(match_id, user_ids):
    """Collect confirmed and non-confirmed rows, request snapshots and point evidence."""
    match_id = int(match_id)
    ids = ",".join(str(int(uid)) for uid in user_ids)
    match = query_json(f"SELECT JSON_OBJECT('matchId',id,'status',status,'capacity',max_participant) "
                       f"FROM match_db.matches WHERE id={match_id};")
    require(len(match) == 1, "Test match not found")
    participants = query_json(f"SELECT JSON_OBJECT('userId',user_id,'requestId',request_id,"
                              f"'role',role,'status',participation_status) "
                              f"FROM match_db.match_participants WHERE match_id={match_id};")
    requests = query_json("SELECT payload FROM match_db.outbox_event "
                          "WHERE event_type='match.deposit-request.state' "
                          f"AND JSON_EXTRACT(payload,'$.matchId')={match_id};")
    history = query_json(f"SELECT JSON_OBJECT('userId',user_id,'requestId',request_id,'reason',reason,"
                        f"'delta',delta,'before',before_points,'after',after_points) "
                        f"FROM user_db.point_history WHERE match_id={match_id};")
    users = query_json(f"SELECT JSON_OBJECT('userId',id,'points',points,'status',status,"
                      f"'suspended',suspended_until IS NOT NULL) FROM user_db.`user` WHERE id IN ({ids});")
    return dict(match=match[0], participants=participants, requests=requests, history=history, users=users)


def prepare(member_count):
    """Create fresh funded accounts and three confirmed matches; persist only a ready fixture."""
    (RUN / "verification.json").unlink(missing_ok=True)
    run_id = secrets.token_hex(4)
    password = secrets.token_urlsafe(12)
    facility = int(sql(f"""
        START TRANSACTION;
        INSERT INTO facility_db.location(region,name) VALUES ('성동구','k6-{run_id}');
        INSERT INTO facility_db.service(location_id,service_id,name,start_time,end_time,active)
        VALUES (LAST_INSERT_ID(),'k6-{run_id}','k6 facility','08:00','22:00',1);
        SELECT LAST_INSERT_ID();
        COMMIT;
    """))
    members = []
    for i in range(member_count + 1):
        name = f"k6{run_id}{i:03}"
        email = name + "@example.invalid"
        code, _ = request("/user-service/api/users", dict(email=email, password=password, nickname=name,
                          district="SEONGDONG", gender="MALE", sportTypes=[]))
        require(code == 201, "Signup was not created")
        _, login = request("/user-service/api/auth/login", dict(email=email, password=password))
        members.append(dict(userId=login["userId"], token=login["accessToken"]))
    owner = members.pop(0)
    day = (datetime.now(timezone(timedelta(hours=9))) + timedelta(days=3)).date().isoformat()
    for i in range(3):
        code, accepted = request("/match-service/api/matches", dict(
            title=f"k6-{run_id}-{i}", serviceId=facility, startAt=day+"T12:00:00",
            endAt=day+"T13:00:00", maxParticipant=CAPACITY, skillLevel="BEGINNER",
            sportType="FUTSAL", genderGroup="MIXED"), owner["token"])
        require(code == 202 and accepted.get("status") == "PENDING", "Expected Saga creation (202)")
        wait_created(accepted, owner["token"])
    db = read_db(accepted["matchId"], [m["userId"] for m in [owner, *members]])
    require(db["match"]["status"] == "RECRUITING" and len(db["participants"]) == 1
            and db["participants"][0]["status"] == "CONFIRMED"
            and db["participants"][0]["userId"] == owner["userId"], "Initial owner not confirmed")
    balances = {u["userId"]: u["points"] for u in db["users"]}
    require(len(balances) == member_count + 1 and all(u["status"] == "ACTIVE" and not u["suspended"]
            for u in db["users"]), "Test accounts must be active and unsuspended")
    require(balances[owner["userId"]] == 20 and all(balances[m["userId"]] == 50 for m in members),
            "Unexpected signup/deposit balances")
    owner["baselinePoints"] = balances[owner["userId"]]
    fixture = dict(runId=run_id, matchId=accepted["matchId"], capacity=CAPACITY, owner=owner, members=members)
    RUN.mkdir(exist_ok=True)
    (RUN / "participants.json").write_text(json.dumps(fixture), encoding="utf-8")
    print(f"READY run={run_id} match={accepted['matchId']} members={member_count} confirmed=1 capacity=10")


def validate(fixture, db, result):
    """Reconcile k6 terminal counts with R10 requests, participants and per-request point history."""
    require(result["runId"] == fixture["runId"] and result["matchId"] == fixture["matchId"],
            "Stale k6 result: prepare and join must belong to the same run")
    members = {m["userId"] for m in fixture["members"]}
    owner = fixture["owner"]["userId"]
    allowed = members | {owner}
    expected = min(len(members), fixture["capacity"] - 1)
    metrics = result["metrics"]
    count = lambda name: int(metrics[name]["values"]["count"])
    require(count("join_attempts") == len(members) and count("join_unexpected") == 0,
            "Missing attempts, unexpected responses, poll failures or unfinished requests")
    require(count("join_confirmed") == expected and count("join_capacity_rejected") == len(members)-expected,
            "Unexpected confirmed/capacity-rejected counts")
    rows = db["participants"]
    require(len({p["userId"] for p in rows}) == len(rows) and all(p["userId"] in allowed for p in rows),
            "Duplicate or foreign participant")
    confirmed = {p["userId"] for p in rows if p["status"] == "CONFIRMED"}
    require(len(confirmed) == expected + 1 and owner in confirmed, "Confirmed capacity/owner mismatch")
    require(sum(p["role"] == "OWNER" for p in rows) == 1
            and all((p["role"] == "OWNER") == (p["userId"] == owner) for p in rows), "Invalid participant roles")
    require(db["match"]["capacity"] == fixture["capacity"] and db["match"]["status"] ==
            ("CLOSED" if len(confirmed) == fixture["capacity"] else "RECRUITING"), "Match state mismatch")
    requests = {r["requestId"]: r for r in db["requests"]}
    require(len(requests) == len(db["requests"]) == len(rows), "Request/participant count mismatch")
    require(count("join_accepted") == len(requests)-1, "202 count differs from stored requests")
    require({h["requestId"] for h in db["history"]} <= requests.keys(), "History without a request")
    refunded = 0
    for p in rows:
        r = requests.get(p["requestId"])
        require(r is not None and r["userId"] == p["userId"] and r["matchId"] == fixture["matchId"]
                and r["status"] == p["status"], "Request and participant state disagree")
        history = [h for h in db["history"] if h["requestId"] == p["requestId"]]
        require(all(h["userId"] == p["userId"] and h["after"]-h["before"] == h["delta"] for h in history),
                "Point history identity/arithmetic mismatch")
        reasons = Counter((h["reason"], h["delta"]) for h in history)
        if p["status"] == "CONFIRMED":
            require(r["refundStatus"] == "NOT_REQUIRED" and reasons == Counter({("DEPOSIT_CHARGED", -10): 1}),
                    "Confirmed request must have exactly one charge and no refund")
        else:
            require(p["status"] in {"FAILED", "CANCELLED"} and r["failureCode"] in CAPACITY_ERRORS,
                    "Pending or non-capacity failure in test fixture")
            if r["refundStatus"] == "COMPLETED":
                refunded += 1
                require(reasons in [Counter({("DEPOSIT_CHARGED", -10): 1, ("DEPOSIT_RETURNED", 10): 1}),
                                    Counter({("DEPOSIT_CANCELLED", 0): 1})], "Missing or duplicate refund history")
            else:
                require(r["refundStatus"] == "NOT_REQUIRED" and not history, "Unfinished refund or unreturned charge")
    require(count("join_refund_completed") == refunded, "API/DB refund count mismatch")
    require(len(db["users"]) == len(allowed) and {u["userId"] for u in db["users"]} == allowed,
            "Missing fixture users")
    for u in db["users"]:
        expected_points = fixture["owner"]["baselinePoints"] if u["userId"] == owner else (
            40 if u["userId"] in confirmed else 50)
        require(u["status"] == "ACTIVE" and not u["suspended"] and u["points"] == expected_points,
                f"Unexpected points/status for user {u['userId']}")
    return dict(runId=fixture["runId"], matchId=fixture["matchId"], applicants=len(members),
                accepted=count("join_accepted"), confirmed=expected, capacityRejected=len(members)-expected,
                refundsCompleted=refunded, dbConfirmed=len(confirmed), capacity=fixture["capacity"], verified=True)


def verify():
    """Fail on stale summaries or unsettled deposits and save token-free verification evidence."""
    (RUN / "verification.json").unlink(missing_ok=True)
    fixture = json.loads((RUN / "participants.json").read_text())
    result = json.loads((RUN / "join-results.json").read_text())
    ids = [m["userId"] for m in [fixture["owner"], *fixture["members"]]]
    report = validate(fixture, read_db(fixture["matchId"], ids), result)
    (RUN / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "verify"])
    parser.add_argument("--members", type=int, default=100, choices=range(1, 101), metavar="1..100")
    args = parser.parse_args()
    prepare(args.members) if args.command == "prepare" else verify()
