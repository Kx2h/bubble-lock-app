import { id, nowIso, recordEnvelope } from './integration-utils.mjs';

export class HandoverCoordinator {
  constructor({ store, clock = () => new Date().toISOString() } = {}) {
    if (!store) throw new Error('HandoverCoordinator requires IntegrationStore');
    this.store = store;
    this.clock = clock;
    this.edges = new Map();
    this.sessions = new Map();
  }

  registerEdge(edge) {
    if (!edge?.edgeId) throw new Error('edgeId is required');
    const next = { edgeId: edge.edgeId, region: edge.region ?? 'local', location: edge.location ?? null, health: edge.health ?? 'HEALTHY', load: edge.load ?? 0, capabilities: edge.capabilities ?? [], subscriptions: [...(edge.subscriptions ?? [])], securityContext: edge.securityContext ?? {}, updatedAt: nowIso(this.clock) };
    this.edges.set(next.edgeId, next);
    return next;
  }

  updateHealth(edgeId, patch) {
    const edge = this.edges.get(edgeId);
    if (!edge) throw new Error(`Edge ${edgeId} is not registered`);
    Object.assign(edge, patch, { updatedAt: nowIso(this.clock) });
    return edge;
  }

  selectEdge({ location = null, capabilities = [] } = {}) {
    const candidates = [...this.edges.values()].filter((edge) => edge.health === 'HEALTHY' && capabilities.every((capability) => edge.capabilities.includes(capability)));
    candidates.sort((a, b) => (a.load - b.load) || String(a.edgeId).localeCompare(String(b.edgeId)));
    return candidates[0] ?? null;
  }

  async handover({ ueId, targetEdgeId, grantId = null, subscriptionIds = [], reason = 'location-health' }) {
    const target = this.edges.get(targetEdgeId);
    if (!target || target.health !== 'HEALTHY') throw new Error('Target edge health review required');
    const prior = this.sessions.get(ueId) ?? { ueId, epoch: 0, state: 'DETACHED', edgeId: null, subscriptions: [], securityContext: {} };
    const next = { ...prior, state: 'DRAINING', handoverId: id('handover'), previousEdgeId: prior.edgeId, edgeId: targetEdgeId, targetEdgeId, grantId, reason, startedAt: nowIso(this.clock), epoch: prior.epoch + 1, subscriptions: [...new Set(subscriptionIds)], securityContext: { ...prior.securityContext, ...target.securityContext, grantId, epoch: prior.epoch + 1 } };
    this.sessions.set(ueId, next);
    await this.store.put('handovers', ueId, recordEnvelope({ type: 'HANDOVER_DRAINING', ...next }, this.clock));
    next.state = 'ACTIVE';
    next.completedAt = nowIso(this.clock);
    await this.store.put('handovers', ueId, recordEnvelope({ type: 'HANDOVER_ACTIVE', ...next }, this.clock));
    return next;
  }

  async rollback(ueId, reason = 'handover-review') {
    const session = this.sessions.get(ueId) ?? this.store.get('handovers', ueId);
    if (!session) throw new Error('Handover session is required');
    const target = session.previousEdgeId ?? session.edgeId;
    const next = { ...session, state: 'ROLLED_BACK', edgeId: target, rollbackReason: reason, rolledBackAt: nowIso(this.clock), epoch: session.epoch + 1 };
    this.sessions.set(ueId, next);
    await this.store.put('handovers', ueId, recordEnvelope({ type: 'HANDOVER_ROLLBACK', ...next }, this.clock));
    return next;
  }

  status(ueId) {
    return this.sessions.get(ueId) ?? this.store.get('handovers', ueId) ?? null;
  }
}
