export const MOBILE_CONTRACT = 'privacy-bubble.mobile.v1';
export const MAX_KEYRINGS = 100;
const TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const finite = value => typeof value === 'number' && Number.isFinite(value);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && [Object.prototype, null].includes(Object.getPrototypeOf(value));
const ownRecord = (collection, id) => record(collection) && Object.hasOwn(collection, id) && record(collection[id]) ? collection[id] : null;
function time(value) {
  if (typeof value !== 'string' || !TIME.test(value)) return null;
  const year = Number(value.slice(0, 4)), month = Number(value.slice(5, 7)), day = Number(value.slice(8, 10));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || Number(value.slice(11, 13)) > 23
      || Number(value.slice(14, 16)) > 59 || Number(value.slice(17, 19)) > 59) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}
const text = (value, max) => typeof value === 'string' && value.trim() && value.length <= max && !/[\x00-\x1f\x7f]/.test(value);

export function validateBindings(bindings) {
  if (!Array.isArray(bindings) || bindings.length > 1000) throw new Error('Invalid keyring bindings');
  const ids = new Set(), counts = new Map();
  for (const b of bindings) {
    if (!record(b) || !['id', 'name', 'ueId', 'principalIds'].every(field => Object.hasOwn(b, field))
        || !text(b.id, 96) || ids.has(b.id) || !text(b.name, 96) || !text(b.ueId, 128)
        || !Array.isArray(b.principalIds) || b.principalIds.length > 100 || !b.principalIds.every(p => text(p, 256))
        || new Set(b.principalIds).size !== b.principalIds.length || (b.grantId !== undefined && !text(b.grantId, 128))
        || (b.grantSubjectId !== undefined && !text(b.grantSubjectId, 128))
        || (b.active !== undefined && typeof b.active !== 'boolean')) throw new Error('Invalid keyring binding');
    ids.add(b.id);
    for (const p of b.principalIds) {
      const count = (counts.get(p) ?? 0) + 1;
      if (count > MAX_KEYRINGS) throw new Error('Too many keyrings for one principal');
      counts.set(p, count);
    }
  }
  return bindings;
}

function grantPermits(state, binding, subject, now) {
  if (binding.active === false) return false;
  if (!binding.grantId) return true;
  const grant = ownRecord(state.grants, binding.grantId);
  if (!grant || grant.status !== 'ACTIVE' || grant.principalId !== subject || time(grant.expiresAt) === null || time(grant.expiresAt) <= now
      || grant.grantId !== binding.grantId || !text(grant.consentId, 128) || !Number.isSafeInteger(grant.revision) || grant.revision < 1
      || grant.subjectId !== (binding.grantSubjectId ?? binding.ueId)
      || !Array.isArray(grant.scopes) || !grant.scopes.every(s => text(s, 256)) || !grant.scopes.some(s => s === 'location:read' || s === '*')) return false;
  const consent = ownRecord(state.consents, grant.consentId);
  return !!consent && consent.consentId === grant.consentId && consent.status === 'ACTIVE' && consent.revision === grant.revision
    && Array.isArray(consent.scopes) && consent.scopes.every(s => text(s, 256)) && consent.scopes.some(s => s === 'location:read' || s === '*')
    && consent.subjectId === grant.subjectId
    && time(consent.expiresAt) !== null && time(consent.expiresAt) > now;
}

function empty(binding, status) {
  return { id: binding.id, name: binding.name, status, latitude: null, longitude: null, accuracyMeters: null,
    observedAt: null, receivedAt: null, expiresAt: null, source: 'unknown', connectivity: 'unknown', batteryPercent: null };
}

function observed(event) { return event.observedAt ?? event.location?.observedAt ?? event.position?.observedAt ?? event.timestamp; }

function latestEvents(events, ueIds) {
  const candidates = new Map();
  for (const [index, event] of events.entries()) {
    if (!record(event) || event.service !== 'Location' || !ueIds.has(event.ueId)) continue;
    let candidate = candidates.get(event.ueId);
    if (!candidate) { candidate = { chosen: null, newest: -Infinity, chosenIndex: -1, undated: null, undatedIndex: -1 }; candidates.set(event.ueId, candidate); }
    const when = time(observed(event));
    if (when === null) { candidate.undated = event; candidate.undatedIndex = index; continue; }
    if (when >= candidate.newest) { candidate.chosen = event; candidate.newest = when; candidate.chosenIndex = index; }
  }
  return new Map([...candidates].map(([id, c]) => [id, c.undatedIndex > c.chosenIndex ? c.undated : c.chosen]));
}

function location(binding, event, now, maxAgeMs) {
  if (!event) return empty(binding, 'unavailable');
  const point = event.location ?? event.position;
  if (event.permission === 'denied' || point?.status === 'denied') return empty(binding, 'denied');
  const lat = point?.lat, lon = point?.lon;
  const observedMs = time(observed(event)), receivedMs = time(event.receivedAt ?? event.timestamp);
  const accuracy = point?.accuracyMeters ?? event.accuracyMeters ?? null;
  if (!record(point) || !finite(lat) || !finite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180
      || observedMs === null || receivedMs === null || observedMs > now + 30_000 || receivedMs > now + 30_000 || observedMs > receivedMs + 30_000
      || (accuracy !== null && (!finite(accuracy) || accuracy < 0 || accuracy > 10_000_000))) return empty(binding, 'unavailable');
  const expiryMs = Object.hasOwn(point, 'expiresAt') ? time(point.expiresAt)
    : Object.hasOwn(event, 'expiresAt') ? time(event.expiresAt) : observedMs + maxAgeMs;
  if (expiryMs === null || expiryMs < observedMs) return empty(binding, 'unavailable');
  const expires = Math.min(expiryMs, observedMs + maxAgeMs);
  const battery = event.batteryPercent ?? event.battery;
  return { id: binding.id, name: binding.name, status: expires > now ? 'available' : 'stale', latitude: lat, longitude: lon,
    accuracyMeters: accuracy, observedAt: new Date(observedMs).toISOString(), receivedAt: new Date(receivedMs).toISOString(),
    expiresAt: new Date(expires).toISOString(), source: 'mec_location',
    connectivity: ['online', 'offline'].includes(event.connectivity) ? event.connectivity : 'unknown',
    batteryPercent: Number.isInteger(battery) && battery >= 0 && battery <= 100 ? battery : null };
}

export function readLocations({ state, bindings, subject, now = Date.now(), maxAgeMs = 300_000 }) {
  if (!record(state) || !Object.hasOwn(state, 'events') || !record(state.events) || !text(subject, 256)
      || !Number.isSafeInteger(now) || Math.abs(now) > 8_639_999_913_600_000
      || !Number.isFinite(maxAgeMs) || maxAgeMs < 1000 || maxAgeMs > 86_400_000) throw new Error('Invalid location store');
  const events = Object.values(state.events);
  if (events.length > 20_000) throw new Error('Location store exceeds read bound');
  validateBindings(bindings);
  const visible = bindings.filter(b => b.principalIds.includes(subject)).map(binding => ({ binding, permitted: grantPermits(state, binding, subject, now) }));
  const latest = latestEvents(events, new Set(visible.filter(b => b.permitted).map(b => b.binding.ueId)));
  const keyrings = visible.map(({ binding, permitted }) => permitted ? location(binding, latest.get(binding.ueId), now, maxAgeMs) : empty(binding, 'denied'));
  return { contractId: MOBILE_CONTRACT, docRevision: '2.0.0', generatedAt: new Date(now).toISOString(), keyrings };
}
