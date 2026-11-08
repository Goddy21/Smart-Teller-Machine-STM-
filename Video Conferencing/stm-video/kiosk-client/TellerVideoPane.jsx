// React 18 component for the kiosk touch app. Shows the teller once connected; hides itself if the teller's
// camera is off or the call drops. Audio is played separately by VideoSession, so this <video> is muted.
import { useEffect, useRef, useState } from "react";

export function TellerVideoPane({ session, connectingText = "Connecting you to a teller…", cameraOffText = "Your teller has turned their camera off" }) {
  const ref = useRef(null);
  const [live, setLive] = useState(false);
  useEffect(() => {
    session.setTellerVideo(ref.current, setLive);
    return () => session.setTellerVideo(null, null);
  }, [session]);
  return (
    <div className="teller-pane" style={{ position: "relative", background: "#0b121a", borderRadius: 12, overflow: "hidden", aspectRatio: "16/9" }}>
      <video ref={ref} autoPlay playsInline muted style={{ width: "100%", height: "100%", objectFit: "cover", display: live ? "block" : "none" }} />
      {!live && (
        <p role="status" style={{ position: "absolute", inset: 0, margin: 0, display: "grid", placeItems: "center", color: "#cfd8e1", textAlign: "center", padding: 16 }}>
          {session.hasTeller ? cameraOffText : connectingText}
        </p>
      )}
    </div>
  );
}
