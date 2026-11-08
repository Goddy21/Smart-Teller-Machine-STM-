import redis, threading, collections
R="/mnt/user-data/outputs/stm-video/call-control/src/main/resources/lua/"
claim,cas,due=[open(R+f).read() for f in ("claim.lua","cas.lua","due.lua")]
P="{stm}:"
def conn(): return redis.Redis(host="localhost",port=6379,decode_responses=True)
r=conn(); r.flushall()
srv=r.info()["redis_version"]; print("real Redis", srv)

def call(i,lang="en",state="QUEUED"):
    r.hset(P+"call:"+i,mapping={"id":i,"state":state,"lang":lang}); r.zadd(P+"queue",{i:1})
def teller(i,av="1",langs=("en",)):
    r.hset(P+"teller:"+i,mapping={"available":av,"langs":","+",".join(langs)+","}); r.sadd(P+"tellers",i)
def do_claim(c,i,lang="en"): return c.eval(claim,3,P+"call:"+i,P+"queue",P+"tellers",P,lang,i)

# ---- functional (same cases as the emulator run, now on real Redis)
call("c1","sw"); teller("t_en"); teller("t_sw",langs=("en","sw"))
assert do_claim(r,"c1","sw")=="t_sw" and r.hget(P+"teller:t_sw","available")=="0"
assert r.hget(P+"call:c1","state")=="RINGING" and r.zscore(P+"queue","c1") is None
assert do_claim(r,"c1","sw") is None                      # already ringing: cannot be claimed again
r.sadd(P+"tellers","ghost"); call("c4"); assert do_claim(r,"c4")=="t_en" and not r.sismember(P+"tellers","ghost")
k=P+"call:c6"; r.hset(k,mapping={"state":"RINGING","tellerId":"x"})
assert r.eval(cas,1,k,"RINGING","QUEUED","CLEAR","")==1 and r.hget(k,"tellerId") is None
assert r.eval(cas,1,k,"RINGING","CONNECTED","NONE","")==0
print("PASS functional cases on real Redis")

# ---- race 1: 50 queued calls, 10 tellers, 16 threads claiming concurrently
r.flushall()
for i in range(50): call(f"c{i}")
for i in range(10): teller(f"t{i}")
results=[]; lock=threading.Lock()
def worker(ids):
    c=conn()
    for i in ids:
        t=do_claim(c,f"c{i}")
        if t:
            with lock: results.append((f"c{i}",t))
ths=[threading.Thread(target=worker,args=(range(n,50,16),)) for n in range(16)]
[t.start() for t in ths]; [t.join() for t in ths]
tellers_used=collections.Counter(t for _,t in results)
assert len(results)==10, f"expected 10 successful claims, got {len(results)}"
assert all(v==1 for v in tellers_used.values()), f"a teller was rung twice: {tellers_used}"
assert len({c for c,_ in results})==10
assert all(r.hget(P+"teller:"+f"t{i}","available")=="0" for i in range(10))
print("PASS race 1: 50 calls x 10 tellers x 16 threads -> exactly 10 claims, no teller double-rung")

# ---- race 2: one call, 40 threads claim it at once -> exactly one winner
r.flushall(); call("solo"); [teller(f"t{i}") for i in range(40)]
wins=[]
def w2():
    t=do_claim(conn(),"solo")
    if t: 
        with lock: wins.append(t)
ths=[threading.Thread(target=w2) for _ in range(40)]; [t.start() for t in ths]; [t.join() for t in ths]
assert len(wins)==1, f"call claimed {len(wins)} times"
assert sum(1 for i in range(40) if r.hget(P+f"teller:t{i}","available")=="0")==1, "extra tellers were marked busy"
print("PASS race 2: 40 threads claim one call -> exactly one winner, one teller busy")

# ---- race 3: CAS RINGING->CONNECTED from 30 threads (e.g. accept vs. ring-timeout vs. abandon)
r.flushall(); r.hset(P+"call:x",mapping={"state":"RINGING"})
oks=[]
def w3():
    v=conn().eval(cas,1,P+"call:x","RINGING","CONNECTED","NONE","")
    if v==1:
        with lock: oks.append(1)
ths=[threading.Thread(target=w3) for _ in range(30)]; [t.start() for t in ths]; [t.join() for t in ths]
assert len(oks)==1 and r.hget(P+"call:x","state")=="CONNECTED"
print("PASS race 3: 30 concurrent state changes -> exactly one succeeds")

# ---- race 4: due-deadline pop from 8 workers: every item handled exactly once
r.flushall(); N=1000
r.zadd(P+"deadlines",{f"ring:c{i}":i for i in range(N)})
got=[]
def w4():
    c=conn()
    while True:
        b=c.eval(due,1,P+"deadlines",10**9)
        if not b: return
        with lock: got.extend(b)
ths=[threading.Thread(target=w4) for _ in range(8)]; [t.start() for t in ths]; [t.join() for t in ths]
assert len(got)==N and len(set(got))==N
print("PASS race 4: 1000 deadlines x 8 workers -> each handled exactly once")
