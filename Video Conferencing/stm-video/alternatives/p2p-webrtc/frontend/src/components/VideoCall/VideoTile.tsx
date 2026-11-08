import { useEffect, useRef } from "react";

interface VideoTileProps {
  stream?: MediaStream | null;
  label: string;
  /** Mute the element's audio. Always true for your own preview, to prevent echo. */
  muted?: boolean;
  mirror?: boolean;
  emptyText?: string;
  className?: string;
}

export function VideoTile({ stream, label, muted = false, mirror = false, emptyText = "Waiting for video", className = "" }: VideoTileProps) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => { if (ref.current) ref.current.srcObject = stream ?? null; }, [stream]);
  return (
    <div className={`vt ${className}`}>
      <video ref={ref} autoPlay playsInline muted={muted} style={mirror ? { transform: "scaleX(-1)" } : undefined} />
      {!stream && <div className="vt-empty">{emptyText}</div>}
      <span className="vt-tag">{label}</span>
    </div>
  );
}
