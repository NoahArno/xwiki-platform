import fs from 'node:fs';
import path from 'node:path';

export function ensureDirectory(directory) {
  fs.mkdirSync(directory, { recursive: true });
}

export function writeJson(filePath, data) {
  ensureDirectory(path.dirname(filePath));
  fs.writeFileSync(filePath, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
}

export function nowIso() {
  return new Date().toISOString();
}

export function createRecorder(config) {
  const events = [];
  return {
    events,
    event(type, payload = {}) {
      const entry = { time: nowIso(), type, ...payload };
      events.push(entry);
      const label = payload.userId ? `[${payload.userId}]` : '[run]';
      console.log(`${label} ${type}`, compactPayload(payload));
      return entry;
    },
    flush(name = 'events.json') {
      writeJson(path.join(config.artifactsDir, name), events);
    }
  };
}

function compactPayload(payload) {
  const copy = { ...payload };
  delete copy.userId;
  return JSON.stringify(copy);
}
