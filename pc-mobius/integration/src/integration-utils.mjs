import { createHash, randomUUID } from 'node:crypto';

export const DOC_REVISION = '2.0.0';
export const INTEGRATION_CONTRACT = 'privacy-bubble.integration.v1';

export function nowIso(clock) {
  return (clock ?? (() => new Date().toISOString()))();
}

export function id(prefix) {
  return `${prefix}-${randomUUID()}`;
}

export function sha256(value) {
  return createHash('sha256').update(typeof value === 'string' ? value : JSON.stringify(value)).digest('hex');
}

export function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]));
  return value;
}

export function approvalHash(value) {
  return sha256(stable(value));
}

export function requireString(value, name) {
  if (typeof value !== 'string' || value.trim().length === 0) throw new Error(`${name} is required`);
  return value.trim();
}

export function requireArray(value, name) {
  if (!Array.isArray(value) || value.length === 0) throw new Error(`${name} requires at least one item`);
  return value;
}

export function parseBearer(value) {
  const token = String(value ?? '').replace(/^Bearer\s+/i, '').trim();
  if (!token) throw new Error('Bearer token is required');
  const chunks = token.split('.');
  if (chunks.length === 3) {
    try {
      return JSON.parse(Buffer.from(chunks[1], 'base64url').toString('utf8'));
    } catch {
      return { sub: token };
    }
  }
  try {
    return JSON.parse(Buffer.from(token, 'base64url').toString('utf8'));
  } catch {
    return { sub: token };
  }
}

export function recordEnvelope(event, clock) {
  return {
    contractId: INTEGRATION_CONTRACT,
    docRevision: DOC_REVISION,
    eventId: event.eventId ?? id('evt'),
    timestamp: event.timestamp ?? nowIso(clock),
    ...event
  };
}
