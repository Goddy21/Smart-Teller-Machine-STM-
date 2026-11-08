import psycopg2, threading, datetime, re
J="/mnt/user-data/outputs/stm-video/call-control/"
src=open(J+"src/main/java/com/stm/callcontrol/RecordingService.java").read()
def conn():
    c=psycopg2.connect(host="localhost",dbname="stm",user="stm",password="pw"); c.autocommit=True; return c
c=conn(); cur=c.cursor()
print("real", c.server_version, "(PostgreSQL)")
cur.execute("drop table if exists recordings, call_events, calls cascade"); cur.execute("drop function if exists forbid_mutation() cascade")
# --- a. the shipped migration, verbatim
cur.execute(open(J+"src/main/resources/db/migration/V1__audit.sql").read())
cur.execute("select count(*) from information_schema.tables where table_name in ('calls','call_events','recordings')"); assert cur.fetchone()[0]==3
print("PASS migration V1__audit.sql applies; 3 tables created")

# --- b. append-only: UPDATE, DELETE and TRUNCATE all rejected
cur.execute("insert into call_events values ('c1',1,'REQUESTED',now(),'{}','GENESIS','h1')")
for stmt in ("update call_events set type='X'","delete from call_events","truncate call_events"):
    try: cur.execute(stmt); raise SystemExit("NOT REJECTED: "+stmt)
    except psycopg2.errors.RaiseException as e: assert "append-only" in str(e)
cur.execute("select count(*) from call_events"); assert cur.fetchone()[0]==1
print("PASS call_events rejects UPDATE / DELETE / TRUNCATE")
# the documented gap: a DB admin can disable the trigger (this is why chain hashes + anchoring exist)
cur.execute("alter table call_events disable trigger call_events_no_update")
cur.execute("update call_events set type='TAMPERED'"); cur.execute("alter table call_events enable trigger call_events_no_update")
print("NOTE  a privileged role CAN bypass the trigger (as documented): hash chain and external anchoring are the real defence")

# --- c. idempotent insert, retention arithmetic (statement text must still match the Java source)
ins="INSERT INTO recordings(track_sid, call_id, tx_id, participant, track_name, status, object_key, retention_until) VALUES (%s,%s,%s,%s,%s,'PENDING',%s, now() + make_interval(years => %s)) ON CONFLICT DO NOTHING"
assert "ON CONFLICT DO NOTHING" in src and "make_interval(years => ?)" in src
cur.execute(ins,("TR1","c1","tx1","kiosk:T1","face","k1",7)); a=cur.rowcount
cur.execute(ins,("TR1","c1","tx1","kiosk:T1","face","k1",7)); b=cur.rowcount
assert (a,b)==(1,0)
cur.execute("select retention_until > now()+interval '6 years 364 days' from recordings where track_sid='TR1'"); assert cur.fetchone()[0]
print("PASS duplicate webhook inserts once (only the first starts an egress); retention = now + 7 years")

# --- d. multi-node sealing claim: 6 workers, 40 COMPLETE rows -> each row handed out exactly once
claim="""UPDATE recordings SET status = 'HASHING', hash_claimed_at = now()
         WHERE track_sid IN (SELECT track_sid FROM recordings
                              WHERE status = 'COMPLETE' OR (status = 'HASHING' AND hash_claimed_at < now() - interval '10 minutes')
                              ORDER BY started_at LIMIT 5 FOR UPDATE SKIP LOCKED)
        RETURNING track_sid, call_id, object_key"""
assert "FOR UPDATE SKIP LOCKED" in src and "interval '10 minutes'" in src
cur.execute("delete from recordings")
for i in range(40): cur.execute(ins,(f"S{i}","c9","tx9","kiosk:T1","face",f"k{i}",7))
cur.execute("update recordings set status='COMPLETE'")
got=[]; lock=threading.Lock()
def worker():
    cc=conn(); k=cc.cursor()
    while True:
        k.execute(claim); rows=k.fetchall()
        if not rows: return
        with lock: got.extend(r[0] for r in rows)
ths=[threading.Thread(target=worker) for _ in range(6)]; [t.start() for t in ths]; [t.join() for t in ths]
assert len(got)==40 and len(set(got))==40, (len(got),len(set(got)))
print("PASS sealing claim: 6 concurrent workers, 40 rows -> each claimed exactly once, none missed")
# stale claim reclaimed after 10 minutes, fresh claim left alone
cur.execute("update recordings set status='HASHING', hash_claimed_at=now()-interval '11 minutes' where track_sid='S0'")
cur.execute("update recordings set status='HASHING', hash_claimed_at=now()-interval '1 minute' where track_sid='S1'")
cur.execute(claim); ids={r[0] for r in cur.fetchall()}
assert "S0" in ids and "S1" not in ids
print("PASS a claim held by a dead node is retaken after 10 minutes; a live one is not")

# --- e. recording health: newest row per (participant, track), teller-cam excluded, needs 4
health="SELECT count(*) FROM (SELECT DISTINCT ON (participant, track_name) status FROM recordings WHERE call_id = %s AND track_name <> 'teller-cam' ORDER BY participant, track_name, started_at DESC) t WHERE status IN ('PENDING','STARTING','ACTIVE','COMPLETE','HASHING','SEALED')"
assert "track_name <> 'teller-cam'" in src and "EXPECTED_TRACKS = 4" in src
def setup(rows):
    cur.execute("delete from recordings")
    for n,(p,t,s,off) in enumerate(rows):
        cur.execute(ins,(f"H{n}","hc","tx","x","x","k",7))
        cur.execute("update recordings set participant=%s, track_name=%s, status=%s, call_id='hc', started_at=now()+%s*interval '1 second' where track_sid=%s",(p,t,s,off,f"H{n}"))
def healthy():
    cur.execute(health,("hc",)); return cur.fetchone()[0]>=4
K,T="kiosk:T1","teller:a"
four=[(K,"face","ACTIVE",0),(K,"id-cam","ACTIVE",0),(K,"mic","ACTIVE",0),(T,"mic","ACTIVE",0)]
setup(four); assert healthy()
setup(four+[(T,"teller-cam","FAILED",1)]); assert healthy(), "a failed teller camera must not block"
setup(four[:3]+[(T,"teller-cam","ACTIVE",0)]); assert not healthy(), "teller-cam must not count toward the four"
setup(four[:3]); assert not healthy()
setup(four+[(K,"id-cam","FAILED",5)]); assert not healthy(), "newest row for id-cam failed"
setup(four+[(K,"id-cam","FAILED",-5)]); assert healthy(), "older failure superseded by a newer good row"
setup([(K,"face","START_FAILED",0)]+four[1:]); assert not healthy()
print("PASS recording-health query: 7 scenarios incl. teller-cam optional, newest-row-wins, failure blocks")

# --- f. timestamps round-trip at microsecond precision (the audit hash depends on it)
t=datetime.datetime(2026,11,4,10,15,30,123456,tzinfo=datetime.timezone.utc)
cur.execute("insert into call_events values ('t-micro',1,'X',%s,'{}','p','h')",(t,)); cur.execute("select at from call_events where call_id='t-micro'")
assert cur.fetchone()[0]==t
print("PASS timestamptz keeps microseconds exactly")
