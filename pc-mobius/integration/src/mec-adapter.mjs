import { DOC_REVISION, id, nowIso, recordEnvelope, requireString } from './integration-utils.mjs';

function pointInGeofence(point, geofence) {
  if (!geofence || !point) return true;
  if (geofence.type === 'circle') {
    const earth = 6371000;
    const rad = (value) => value * Math.PI / 180;
    const dLat = rad(point.lat - geofence.lat);
    const dLon = rad(point.lon - geofence.lon);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(geofence.lat)) * Math.cos(rad(point.lat)) * Math.sin(dLon / 2) ** 2;
    return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) <= geofence.radiusM;
  }
  if (geofence.type === 'bbox') return point.lat >= geofence.minLat && point.lat <= geofence.maxLat && point.lon >= geofence.minLon && point.lon <= geofence.maxLon;
  return true;
}

export class MecAdapter {
  constructor({ serviceName, endpoint, queryImpl = null, clock = () => new Date().toISOString() } = {}) {
    this.serviceName = requireString(serviceName, 'serviceName');
    this.endpoint = endpoint ?? `mec://${serviceName}`;
    this.queryImpl = queryImpl;
    this.clock = clock;
  }

  async query(request) {
    const response = this.queryImpl ? await this.queryImpl(request) : { serviceName: this.serviceName, endpoint: this.endpoint, ...request, observedAt: nowIso(this.clock) };
    return recordEnvelope({ type: 'MEC_QUERY', serviceName: this.serviceName, endpoint: this.endpoint, request, response }, this.clock);
  }
}

export class MecCallbackIngress {
  constructor({ store, receiptJournal = null, clock = () => new Date().toISOString(), locationAdapter = null, rnisAdapter = null } = {}) {
    if (!store) throw new Error('MecCallbackIngress requires IntegrationStore');
    this.store = store;
    this.receiptJournal = receiptJournal;
    this.clock = clock;
    this.adapters = { Location: locationAdapter, RNIS: rnisAdapter };
    this.handlers = new Set();
  }

  onEvent(handler) {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async ingest(rawEvent) {
    requireString(rawEvent?.eventId, 'eventId');
    requireString(rawEvent?.service, 'service');
    requireString(rawEvent?.ueId, 'ueId');
    const prior = this.store.get('events', rawEvent.eventId);
    if (prior) return { ...prior, status: 'DUPLICATE' };
    const event = recordEnvelope({ ...rawEvent, status: 'RECEIVED', serviceRevision: DOC_REVISION }, this.clock);
    await this.store.put('events', event.eventId, event);
    await this.store.append('receipts', event);
    if (this.receiptJournal) await this.receiptJournal.append(event);
    const outcomes = [];
    for (const handler of this.handlers) outcomes.push(await handler(event));
    const reconciled = { ...event, status: 'RECONCILED', outcomeCount: outcomes.length, outcomes };
    await this.store.put('events', event.eventId, reconciled);
    return reconciled;
  }

  async query(service, request) {
    const adapter = this.adapters[service];
    if (!adapter) throw new Error(`MEC service ${service} is not registered`);
    return adapter.query(request);
  }
}

export class MecPolicyReconciler {
  constructor({ store, policyService, handoverCoordinator = null, clock = () => new Date().toISOString() } = {}) {
    this.store = store;
    this.policyService = policyService;
    this.handoverCoordinator = handoverCoordinator;
    this.clock = clock;
  }

  async reconcileApprovedIntent(event) {
    const intent = event.intent ?? {};
    const geofence = intent.geofence;
    const location = event.location ?? event.position;
    const allowed = pointInGeofence(location, geofence) && (event.health?.status ?? 'HEALTHY') === 'HEALTHY';
    const outcome = recordEnvelope({ type: 'MEC_POLICY_RECONCILE', eventId: event.eventId, ueId: event.ueId, service: event.service, policyId: intent.policyId ?? null, allowed, reason: allowed ? 'location-health-accepted' : 'location-health-review', grantId: intent.grantId ?? null }, this.clock);
    if (allowed && this.handoverCoordinator && intent.targetEdgeId) await this.handoverCoordinator.handover({ ueId: event.ueId, targetEdgeId: intent.targetEdgeId, grantId: intent.grantId, subscriptionIds: intent.subscriptionIds ?? [] });
    await this.store.append('receipts', outcome);
    return outcome;
  }
}

export { pointInGeofence };
