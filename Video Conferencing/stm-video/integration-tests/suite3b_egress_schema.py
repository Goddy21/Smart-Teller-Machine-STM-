"""Twirp silently drops unknown JSON fields, so a misspelled field in the Egress request would not error. This validates the
exact body RecordingService sends, and the egress_ended paths it reads, against LiveKit's real protobuf schema."""
import json
from google.protobuf import json_format
from livekit.protocol import egress as pb
s3={"access_key":"a","secret":"b","region":"us-east-1","endpoint":"http://localhost:9000","bucket":"stm-recordings","force_path_style":True}
body={"room_name":"call-x","track_id":"TR_abc","file":{"filepath":"recordings/tx/call/kiosk-T1-face-TR_abc","s3":s3}}
m=json_format.Parse(json.dumps(body),pb.TrackEgressRequest(),ignore_unknown_fields=False); print("PASS request body matches TrackEgressRequest strictly")
for bad in ({"room_nam":"x"},{**body,"file":{"filepath":"p","s3":{**s3,"secretKey":"b"}}}):
    try: json_format.Parse(json.dumps(bad),pb.TrackEgressRequest(),ignore_unknown_fields=False); raise SystemExit("FAIL control accepted a wrong field")
    except json_format.ParseError: print("PASS control: misspelled field rejected")
info=pb.EgressInfo(egress_id="EG_1",room_name="call-x",status=pb.EgressStatus.EGRESS_COMPLETE)
fr=info.file_results.add(); fr.filename="k.webm"; fr.size=1234567; fr.duration=7_250_000_000
j=json.loads(json_format.MessageToJson(info))
assert j["egressId"]=="EG_1" and j["status"]=="EGRESS_COMPLETE" and j["fileResults"][0]["filename"]=="k.webm" and isinstance(j["fileResults"][0]["size"],str)
print("PASS egress_ended paths: egressId / status (enum name) / fileResults[0].filename; int64 values arrive as strings")
open("/tmp/egress_info.json","w").write(json.dumps(j))
