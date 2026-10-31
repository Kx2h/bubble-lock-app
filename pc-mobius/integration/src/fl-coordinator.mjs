import { approvalHash, id, nowIso, recordEnvelope, requireArray, requireString } from './integration-utils.mjs';

export class FlCoordinator {
  constructor({ store, clock = () => new Date().toISOString() } = {}) {
    if (!store) throw new Error('FlCoordinator requires IntegrationStore');
    this.store = store;
    this.clock = clock;
    this.vms = new Map();
    this.tasks = new Map();
  }

  registerVm(vm) {
    if (!vm?.vmId) throw new Error('vmId is required');
    const next = { vmId: vm.vmId, edgeId: vm.edgeId ?? null, labels: vm.labels ?? [], capacity: vm.capacity ?? 1, health: vm.health ?? 'HEALTHY', attested: vm.attested !== false, endpoint: vm.endpoint ?? null, updatedAt: nowIso(this.clock) };
    this.vms.set(next.vmId, next);
    return next;
  }

  async createRound({ taskId, requiredLabels = [], minParticipants = 1, consentRevision, selectionPolicy = {}, artifactFence = {} }) {
    requireString(taskId, 'taskId');
    const candidates = [...this.vms.values()].filter((vm) => vm.health === 'HEALTHY' && vm.attested && requiredLabels.every((label) => vm.labels.includes(label))).sort((a, b) => (b.capacity - a.capacity) || a.vmId.localeCompare(b.vmId));
    if (candidates.length < minParticipants) throw new Error('FL participant capacity review required');
    const roundId = id('fl-round');
    const round = { roundId, taskId, state: 'RUNNING', roundNumber: 1, consentRevision, requiredLabels, minParticipants, selectionPolicy, artifactFence, participants: candidates.slice(0, Math.max(minParticipants, selectionPolicy.maxParticipants ?? candidates.length)).map((vm) => ({ vmId: vm.vmId, edgeId: vm.edgeId, status: 'SELECTED', update: null })), updates: [], checkpoint: { roundNumber: 1, participantIds: candidates.map((vm) => vm.vmId) }, createdAt: nowIso(this.clock) };
    round.selectionHash = approvalHash(round.participants);
    this.tasks.set(roundId, round);
    return this.persist(round);
  }

  async persist(round) {
    await this.store.put('flRounds', round.roundId, round);
    await this.store.append('receipts', recordEnvelope({ type: 'FL_ROUND', roundId: round.roundId, state: round.state, participantCount: round.participants.length, selectionHash: round.selectionHash }, this.clock));
    return round;
  }

  async submitUpdate({ roundId, participantId, artifactHash, metrics = {}, consentRevision }) {
    const round = this.tasks.get(roundId) ?? this.store.get('flRounds', roundId);
    if (!round || round.state !== 'RUNNING') throw new Error('Running FL round is required');
    const participant = round.participants.find((item) => item.vmId === participantId);
    if (!participant) throw new Error('Participant selection review required');
    if (consentRevision !== round.consentRevision) throw new Error('Consent revision review required');
    if (round.artifactFence?.allowedHashes?.length && !round.artifactFence.allowedHashes.includes(artifactHash)) throw new Error('Artifact hash fence review required');
    const update = { updateId: id('fl-update'), roundId, participantId, artifactHash, metrics, consentRevision, submittedAt: nowIso(this.clock) };
    participant.status = 'UPDATED';
    participant.update = update;
    round.updates.push(update);
    this.tasks.set(roundId, round);
    await this.persist(round);
    return update;
  }

  async finalizeRound(roundId) {
    const round = this.tasks.get(roundId) ?? this.store.get('flRounds', roundId);
    if (!round) throw new Error('FL round is required');
    if (round.updates.length < round.minParticipants) throw new Error('FL quorum review required');
    round.state = 'COMPLETED';
    round.modelHash = approvalHash(round.updates.map((update) => update.artifactHash));
    round.completedAt = nowIso(this.clock);
    await this.persist(round);
    return round;
  }

  async recover(roundId) {
    const round = this.tasks.get(roundId) ?? this.store.get('flRounds', roundId);
    if (!round) throw new Error('FL round is required');
    round.state = 'RECOVERED';
    round.recoveredAt = nowIso(this.clock);
    round.checkpoint = { ...round.checkpoint, resumed: true };
    this.tasks.set(roundId, round);
    await this.persist(round);
    return round;
  }

  serveArtifact(roundId, artifactHash) {
    const round = this.tasks.get(roundId) ?? this.store.get('flRounds', roundId);
    if (!round) throw new Error('FL round is required');
    const allowed = round.updates.some((update) => update.artifactHash === artifactHash) || round.artifactFence?.allowedHashes?.includes(artifactHash);
    if (!allowed) throw new Error('Artifact serving fence review required');
    return { roundId, artifactHash, servedAt: nowIso(this.clock), state: round.state };
  }
}
