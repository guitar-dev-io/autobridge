#!/usr/bin/env node
/*
 * Reference stream host for AutoBridge's remote-stream receiver.
 *
 * Encodes a file or a screen capture as baseline H.264 Annex-B and writes one WebSocket binary
 * frame per access unit. Control messages arrive as JSON text.
 *
 * This is a test fixture. It handles one client, has no authentication, and should only be run
 * on a trusted network.
 */
'use strict';

const { spawn } = require('child_process');
const { WebSocketServer } = require('ws');

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const port = Number(opt('port', '8080'));
const file = opt('file', null);
const capture = opt('capture', null);

if (!file && !capture) {
  console.error('usage: host.js --port 8080 (--file clip.mp4 | --capture "1:none")');
  process.exit(1);
}

/**
 * Baseline profile and a 1s keyframe interval: the receiver configures its decoder from the
 * first access unit carrying an SPS, so frequent keyframes are what make a mid-stream join (and
 * every reconnect) start promptly instead of on the next scene change.
 */
function ffmpegArgs() {
  const input = file
    ? ['-stream_loop', '-1', '-re', '-i', file]
    : ['-f', 'avfoundation', '-framerate', '30', '-i', capture];
  return [
    ...input,
    '-an',
    '-c:v', 'libx264',
    '-profile:v', 'baseline',
    '-level', '3.1',
    '-preset', 'ultrafast',
    '-tune', 'zerolatency',
    '-g', '30',
    '-bf', '0',
    '-pix_fmt', 'yuv420p',
    '-f', 'h264',
    '-bsf:v', 'h264_mp4toannexb',
    'pipe:1',
  ];
}

const START_CODE = Buffer.from([0, 0, 0, 1]);

/**
 * Splits ffmpeg's byte stream back into access units.
 *
 * ffmpeg writes a continuous elementary stream, while the receiver wants one access unit per
 * frame, so the stream is re-split on start codes. An access unit begins at an AUD (9), an SPS
 * (7) or an IDR/non-IDR slice (5/1) that follows a completed one.
 */
function createSplitter(onUnit) {
  let buffer = Buffer.alloc(0);
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    let start = buffer.indexOf(START_CODE);
    if (start < 0) return;
    let next = buffer.indexOf(START_CODE, start + 4);
    while (next >= 0) {
      const unitStart = start;
      // Accumulate NALs until the next one that begins a picture.
      let end = next;
      while (end >= 0) {
        const type = buffer[end + 4] & 0x1f;
        if (type === 9 || type === 7 || type === 5 || type === 1) break;
        end = buffer.indexOf(START_CODE, end + 4);
      }
      if (end < 0) break;
      onUnit(buffer.subarray(unitStart, end));
      start = end;
      next = buffer.indexOf(START_CODE, start + 4);
    }
    buffer = buffer.subarray(start);
  };
}

const server = new WebSocketServer({ port });
console.log(`remote-stream host listening on ws://0.0.0.0:${port}`);

server.on('connection', (socket) => {
  console.log('client connected');
  const encoder = spawn('ffmpeg', ffmpegArgs(), { stdio: ['ignore', 'pipe', 'inherit'] });

  let sent = 0;
  const split = createSplitter((unit) => {
    if (socket.readyState !== socket.OPEN) return;
    socket.send(unit, { binary: true });
    sent += 1;
  });
  encoder.stdout.on('data', split);

  const status = setInterval(() => {
    if (socket.readyState !== socket.OPEN) return;
    socket.send(JSON.stringify({ type: 'status', state: 'playing', positionMs: 0, durationMs: 0 }));
  }, 2000);

  socket.on('message', (raw, isBinary) => {
    if (isBinary) return;
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      return;
    }
    console.log('control', message);
    // A real host would act on these. Printing them is enough to verify the control channel.
  });

  socket.on('close', () => {
    console.log(`client gone after ${sent} access units`);
    clearInterval(status);
    encoder.kill('SIGKILL');
  });
});
