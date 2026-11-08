import asyncio, json, subprocess, logging, aioice
logging.disable(logging.CRITICAL)
JAVA="/tmp/jdk-21.0.5+11/bin/java"
CP="/tmp/tt/out:/usr/share/java/jackson-core.jar:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-annotations.jar"
IP=subprocess.check_output("hostname -I | awk '{print $1}'",shell=True,text=True).strip()
SECRET="THE-SHARED-SECRET-FROM-APPLICATION-YML"
def issue(secret=SECRET,ttl=600,ident="kiosk:T1"):
    j=json.loads(subprocess.check_output([JAVA,"-cp",CP,"com.blueriver.brits.webrtc.service.TurnTool",IP,secret,str(ttl),ident],text=True))
    return [s for s in j["iceServers"] if "username" in s][0]
async def relay(user,cred,transport="udp"):
    kw=dict(turn_server=(IP,3478),turn_username=user,turn_password=cred,turn_transport=transport) if user else dict(turn_server=(IP,3478))
    c=aioice.Connection(ice_controlling=True,**kw)
    try:
        await asyncio.wait_for(c.gather_candidates(),15)
        return [x for x in c.local_candidates if x.type=="relay"]
    except Exception: return []
    finally: await c.close()
fails=0
def check(ok,msg):
    global fails; print(("PASS " if ok else "FAIL ")+msg); fails+= (not ok)
async def main():
    good=issue()
    r=await relay(good["username"],good["credential"]); check(len(r)>=1,f"valid Java-issued credentials get a relay allocation over UDP ({r[0].host}:{r[0].port})" if r else "valid credentials were NOT accepted over UDP")
    r=await relay(good["username"],good["credential"],"tcp"); check(len(r)>=1,"valid credentials also work over TCP (the fallback for locked-down branch networks)")
    teller=issue(ident="teller:a"); r=await relay(teller["username"],teller["credential"]); check(len(r)>=1,"teller identity credentials work too")
    exp=issue(ttl=-30);                   check(not await relay(exp["username"],exp["credential"]),"EXPIRED credentials are refused")
    bad=issue(secret="a-different-secret"); check(not await relay(bad["username"],bad["credential"]),"credentials signed with the wrong secret are refused")
    ts,ident=good["username"].split(":",1)
    check(not await relay(f"{int(ts)+86400}:{ident}",good["credential"]),"extending the expiry on a valid credential is refused (HMAC covers it)")
    check(not await relay(f"{ts}:kiosk-T9",good["credential"]),"re-using a credential under another identity is refused")
    check(not await relay("someone","guess"),"made-up username/password is refused")
    check(not await relay(None,None),"no credentials at all is refused")
asyncio.run(main()); raise SystemExit(fails)
