import { VideoTile } from "./VideoTile";
import "./VideoCall.css";

interface VideoCallProps {
  localStream: MediaStream | null;
  remoteStream: MediaStream | null;
  localLabel?: string;
  remoteLabel?: string;
}

/** Remote video large (with its audio), your own preview small and muted. */
export default function VideoCall({ localStream, remoteStream, localLabel = "You", remoteLabel = "Teller" }: VideoCallProps) {
  return (
    <div className="video-call">
      <VideoTile className="vc-remote" stream={remoteStream} label={remoteLabel} />
      <VideoTile className="vc-local" stream={localStream} label={localLabel} muted mirror />
    </div>
  );
}
