"""Prepare or verify TC-OBS-003 in the dedicated local Compose project."""
import json
import secrets
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path
from urllib.request import Request, urlopen

PROJECT = "sporty-k6-90"
BASE = "http://127.0.0.1:18090"
RUN = Path(__file__).parent / ".run"


def sql(statement):
    result = subprocess.run(
        ["docker", "exec", "-i", PROJECT + "-mariadb-1", "sh", "-c",
         'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" exec mariadb -uroot -BN --default-character-set=utf8mb4'],
        input=statement, text=True, encoding="utf-8", capture_output=True, check=True,
    )
    return result.stdout.strip()


def post(path, data, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(BASE + path, json.dumps(data).encode(), headers)
    with urlopen(request, timeout=30) as response:
        body = response.read()
        return json.loads(body) if body else None


def prepare():
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
    for i in range(101):
        name = f"k6{run_id}{i:03}"
        email = name + "@example.invalid"
        post("/user-service/api/users", dict(email=email, password=password, nickname=name,
             district="SEONGDONG", gender="MALE", sportTypes=[]))
        login = post("/user-service/api/auth/login", dict(email=email, password=password))
        members.append(dict(userId=login["userId"], token=login["accessToken"]))
    owner = members.pop(0)
    day = (date.today() + timedelta(days=3)).isoformat()
    for i in range(3):
        match = post("/match-service/api/matches", dict(
            title=f"k6-{run_id}-{i}", serviceId=facility, startAt=day+"T12:00:00",
            endAt=day+"T13:00:00", maxParticipant=10, skillLevel="BEGINNER",
            sportType="FUTSAL", genderGroup="MIXED"), owner["token"])
    state = snapshot(match)
    if state != ["RECRUITING", 10, 1, 1, 1]:
        raise RuntimeError(f"Unexpected initial state: {state}")
    RUN.mkdir(exist_ok=True)
    (RUN / "participants.json").write_text(json.dumps(dict(matchId=match, members=members)), encoding="utf-8")
    print(f"match={match} ACTIVE members=100 initial={state}")


def snapshot(match):
    fields = sql(f"""SELECT m.status,m.max_participant,COUNT(p.user_id),
      COUNT(DISTINCT p.user_id),SUM(p.role='OWNER') FROM match_db.matches m
      JOIN match_db.match_participants p ON p.match_id=m.id
      WHERE m.id={int(match)} GROUP BY m.id,m.status,m.max_participant;""").split()
    return [fields[0], *map(int, fields[1:])]


def verify():
    fixture = json.loads((RUN / "participants.json").read_text())
    match = fixture["matchId"]
    metrics = json.loads((RUN / "join.json").read_text())["metrics"]
    created = int(metrics["join_created"]["count"])
    actual = snapshot(match)
    if actual != ["CLOSED", 10, 1+created, 1+created, 1] or created != 9:
        raise RuntimeError(f"DB state {actual}, API created {created}")
    print(f"match={match} status={actual[0]} capacity=10 participants={actual[2]} unique={actual[3]} owner=1 API_created={created}")


if __name__ == "__main__":
    command = sys.argv[1:]
    if command == ["prepare"]:
        prepare()
    elif command == ["verify"]:
        verify()
    else:
        raise SystemExit("Usage: python fixture.py prepare|verify")
