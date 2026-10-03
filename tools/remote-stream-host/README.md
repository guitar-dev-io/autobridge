# Reference stream host

A minimal peer for AutoBridge's experimental remote-stream receiver. It exists so the receiver
can be exercised end to end; it is a test fixture, not a product.

What it does: accepts one WebSocket client, runs `ffmpeg` to encode a source as baseline H.264
Annex-B, and writes each access unit as a single binary frame. Control messages arriving as text
are printed, and `open` restarts `ffmpeg` against the new URL if a renderer is wired up.

## Requirements

- Node 18+
- `ffmpeg` on `PATH`
- `npm install ws`

## Run

```bash
# A file, looped — the simplest way to prove the pipeline.
node host.js --port 8080 --file /path/to/clip.mp4

# A screen/window capture (macOS; use x11grab or gdigrab elsewhere).
node host.js --port 8080 --capture "1:none"
```

Then in AutoBridge: Home → Open controller → Remote stream → `<your-ip>:8080` → Save → Turn on.

## Protocol

See [`../../docs/REMOTE_STREAM.md`](../../docs/REMOTE_STREAM.md). In short: binary frames are
H.264 access units, text frames are JSON control messages, and the host may push
`{"type":"status",…}` back.
