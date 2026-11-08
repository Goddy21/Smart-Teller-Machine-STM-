import asyncio, json, subprocess, time, math, collections, urllib.request, urllib.error
from livekit import rtc

JAVA="/tmp/jdk-21.0.5+11/bin/java"
CP="/tmp/lkt/out:/usr/share/java/jackson-core.jar:/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-annotations.jar"
def tok(*a): return subprocess.check_output([JAVA,"-cp",CP,"com.stm.callcontrol.LkTool",*a],text=True).strip()
URL="ws://127.0.0.1:7880"; ROOM="call-itest1"
ok_count=0
def PASS(m):
    global ok_count; ok_count+=1; print("PASS",m)
def FAIL(m): print("FAIL",m); raise SystemExit(1)
def check(c,m): PASS(m) if c else FAIL(m)

counts=collections.Counter(); data=[]; tasks=[]; stop=asyncio.Event()

async def consume(label, track, name):
    stream = rtc.VideoStream(track) if track.kind==rtc.TrackKind.KIND_VIDEO else rtc.AudioStream(track)
    async for _ in stream: counts[(label,name)]+=1

def wire(room,label):
    @room.on("track_subscribed")
    def _(track,pub,part): tasks.append(asyncio.create_task(consume(label,track,f"{part.identity}/{pub.name}")))
    @room.on("data_received")
    def _(dp): data.append((label,dp.topic,bytes(dp.data),dp.participant.identity if dp.participant else None))

async def pump_audio(src):
    f=rtc.AudioFrame.create(48000,1,480); m=f.data
    for i in range(480): m[i]=int(3000*math.sin(2*math.pi*440*i/48000))
    while not stop.is_set(): await src.capture_frame(f); await asyncio.sleep(0.01)
async def pump_video(src,w,h,rgb):
    fr=rtc.VideoFrame(w,h,rtc.VideoBufferType.RGBA,bytearray(bytes(rgb+(255,))*(w*h)))
    while not stop.is_set(): src.capture_frame(fr); await asyncio.sleep(1/15)

async def publish(room,name,kind,source,w=0,h=0,rgb=(0,0,0)):
    lp=room.local_participant
    if kind=="audio":
        s=rtc.AudioSource(48000,1); t=rtc.LocalAudioTrack.create_audio_track(name,s); tasks.append(asyncio.create_task(pump_audio(s)))
    else:
        s=rtc.VideoSource(w,h); t=rtc.LocalVideoTrack.create_video_track(name,s); tasks.append(asyncio.create_task(pump_video(s,w,h,rgb)))
    return await lp.publish_track(t,rtc.TrackPublishOptions(source=source))

async def main():
    # ---------- tokens minted by the REAL Java LiveKitTokens must be accepted; bad ones refused
    r=rtc.Room()
    for label,t in (("expired token",tok("join",ROOM,"kiosk:X","X","true","false","-60")),
                    ("token signed with the wrong secret",tok("badsecret",ROOM,"kiosk:X","X"))):
        try: await r.connect(URL,t); FAIL(label+" was ACCEPTED")
        except Exception: PASS(f"server refuses {label}")
    kiosk,teller,sup=rtc.Room(),rtc.Room(),rtc.Room()
    wire(kiosk,"kiosk"); wire(teller,"teller"); wire(sup,"supervisor")
    await kiosk.connect(URL,tok("join",ROOM,"kiosk:T1","Kiosk T1","true","false","120"))
    await teller.connect(URL,tok("join",ROOM,"teller:a","Teller a","true","false","120"))
    PASS("kiosk and teller join with Java-minted tokens (HS256, room-scoped, 120 s)")

    pubs={}
    pubs["mic"]=await publish(kiosk,"mic","audio",rtc.TrackSource.SOURCE_MICROPHONE)
    pubs["face"]=await publish(kiosk,"face","video",rtc.TrackSource.SOURCE_CAMERA,640,360,(200,60,60))
    pubs["id"]=await publish(kiosk,"id-cam","video",rtc.TrackSource.SOURCE_SCREENSHARE,1280,720,(60,60,200))
    await publish(teller,"mic","audio",rtc.TrackSource.SOURCE_MICROPHONE)
    PASS("kiosk publishes mic + face (camera) + id-cam (second camera as screen-share source); teller publishes mic")

    await sup.connect(URL,tok("join",ROOM,"sup:1","Supervisor","false","true","120"))
    await asyncio.sleep(7)

    # ---------- media actually flows, per track, in each direction
    for who,name,minimum in (("teller","kiosk:T1/face",30),("teller","kiosk:T1/id-cam",30),("teller","kiosk:T1/mic",300),("kiosk","teller:a/mic",300),
                             ("supervisor","kiosk:T1/face",20),("supervisor","kiosk:T1/id-cam",20),("supervisor","teller:a/mic",200)):
        n=counts[(who,name)]; check(n>=minimum,f"{who} received {n} frames of {name} (>= {minimum})")

    # ---------- the second camera is distinguishable by name AND source
    seen={(p.name,p.source) for p in teller.remote_participants["kiosk:T1"].track_publications.values()}
    print("      teller sees kiosk tracks:",sorted((n,str(s).split('.')[-1]) for n,s in seen))
    check({n for n,_ in seen}=={"mic","face","id-cam"},"teller sees exactly the three named kiosk tracks")
    check(("id-cam",rtc.TrackSource.SOURCE_SCREENSHARE) in seen and ("face",rtc.TrackSource.SOURCE_CAMERA) in seen,"face=CAMERA and id-cam=SCREENSHARE sources preserved")

    # ---------- hidden supervisor: invisible to participants, cannot publish
    check("sup:1" not in kiosk.remote_participants and "sup:1" not in teller.remote_participants,"hidden supervisor is invisible to kiosk and teller")
    try: await publish(sup,"x","audio",rtc.TrackSource.SOURCE_MICROPHONE); FAIL("supervisor was allowed to publish")
    except Exception: PASS("supervisor token (canPublish=false) cannot publish")

    # ---------- data channels with the topics the kiosk and console use
    await kiosk.local_participant.publish_data(json.dumps({"v":1,"state":"DEPOSIT_AMOUNT","model":{"amount":900}}).encode(),reliable=True,topic="fsm")
    await teller.local_participant.publish_data(b'{"type":"snapshot"}',reliable=True,topic="ctl")
    await asyncio.sleep(1.5)
    check(any(l=="teller" and t=="fsm" and json.loads(d)["model"]["amount"]==900 and i=="kiosk:T1" for l,t,d,i in data),"topic 'fsm' reaches the teller with the kiosk identity")
    check(any(l=="kiosk" and t=="ctl" and json.loads(d)["type"]=="snapshot" and i=="teller:a" for l,t,d,i in data),"topic 'ctl' (snapshot request) reaches the kiosk")

    # ---------- teller drops: kiosk notices (this is what drives ON_HOLD)
    gone=asyncio.Event()
    @kiosk.on("participant_disconnected")
    def _(p): 
        if p.identity=="teller:a": gone.set()
    await teller.disconnect()
    await asyncio.wait_for(gone.wait(),10); PASS("kiosk observes teller disconnect")
    await asyncio.sleep(1)
    stop.set(); await kiosk.disconnect(); await sup.disconnect()
    for t in tasks: t.cancel()

def http(path,token,body):
    req=urllib.request.Request("http://127.0.0.1:7880"+path,data=json.dumps(body).encode(),headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"},method="POST")
    try:
        with urllib.request.urlopen(req,timeout=45) as r: return r.status,r.read().decode()[:200]
    except urllib.error.HTTPError as e: return e.code,e.read().decode()[:200]
    except TimeoutError: return "forwarded-no-reply",""

def egress_probe():
    # Twirp silently discards unknown JSON fields, so field NAMES are validated against the real protobuf schema in
    # suite3b_egress_schema.py. Here we only check the auth contract of the Egress endpoint.
    s3={"access_key":"a","secret":"b","region":"us-east-1","endpoint":"http://localhost:9000","bucket":"stm-recordings","force_path_style":True}
    body={"room_name":ROOM,"track_id":"TR_does_not_exist","file":{"filepath":"recordings/tx/call/kiosk-T1-face-TR_x","s3":s3}}
    path="/twirp/livekit.Egress/StartTrackEgress"
    good=http(path,tok("record",ROOM),body); print("      record token  ->",good)
    joinonly=http(path,tok("join",ROOM,"kiosk:T1","K","true","false","60"),body); print("      join token    ->",joinonly)
    check(joinonly[0] in (401,403),"Egress API refuses a normal join token (it needs the roomRecord grant)")
    check(good[0] not in (401,403,400),"Egress API accepts the Java-minted record token (503/404/no-reply = reached the egress layer; no worker is running here)")

def webhooks():
    deadline=time.time()+40; rows=[]
    while time.time()<deadline:
        rows=[json.loads(l) for l in open("/tmp/webhooks.jsonl")]
        if any(json.loads(r["body"]).get("event")=="room_finished" for r in rows): break
        time.sleep(2)
    evs=[json.loads(r["body"]) for r in rows]
    kinds=collections.Counter(e.get("event") for e in evs); print("      webhook events:",dict(kinds))
    check(all(r["verified"] for r in rows),f"all {len(rows)} real LiveKit webhooks pass the Java verifyWebhook()")
    check(not any(r["tamperedBody"] or r["tamperedSig"] or r["noAuth"] for r in rows),"altered body, altered signature and missing header are all rejected by verifyWebhook()")
    print("      content-type:",rows[0]["contentType"])
    tp=[e for e in evs if e.get("event")=="track_published"]
    names={(e["participant"]["identity"],e["track"]["name"]) for e in tp}
    check({("kiosk:T1","face"),("kiosk:T1","id-cam"),("kiosk:T1","mic"),("teller:a","mic")}<=names,"track_published carries participant.identity + track.name for all four recorded tracks")
    check(all(e["track"].get("sid","").startswith("TR_") and e["room"]["name"]==ROOM for e in tp),"track_published carries track.sid (TR_...) and room.name")
    pj=[e for e in evs if e.get("event")=="participant_joined"]; pl=[e for e in evs if e.get("event")=="participant_left"]
    check({"kiosk:T1","teller:a"}<={e["participant"]["identity"] for e in pj},"participant_joined carries identity (drives the 'both present' rule)")
    check("teller:a" in {e["participant"]["identity"] for e in pl},"participant_left fires for the teller (drives ON_HOLD)")
    check("room_finished" in kinds,"room_finished arrives after everyone leaves")
    print("      supervisor (hidden) join webhook present:", "sup:1" in {e["participant"]["identity"] for e in pj})

asyncio.run(main()); egress_probe(); webhooks()
print(f"\nSUITE 3: {ok_count} checks passed")
