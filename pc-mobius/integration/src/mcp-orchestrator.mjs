import { approvalHash, id, nowIso, recordEnvelope, requireArray, requireString } from './integration-utils.mjs';

export class McpOrchestrator {
  constructor({ store, policyService, clock = () => new Date().toISOString() } = {}) {
    if (!store || !policyService) throw new Error('McpOrchestrator requires store and policyService');
    this.store = store;
    this.policyService = policyService;
    this.clock = clock;
    this.servers = new Map();
  }

  registerServer({ serverId, domain, tools = {} }) {
    requireString(serverId, 'serverId');
    this.servers.set(serverId, { serverId, domain: domain ?? serverId, tools: { ...tools } });
    return this.servers.get(serverId);
  }

  listServers() {
    return [...this.servers.values()].map(({ serverId, domain, tools }) => ({ serverId, domain, tools: Object.keys(tools) }));
  }

  async startRun({ principal, audience = 'privacy-bubble-mcp', scopes = [], approvalHash: expectedApprovalHash, approval, toolCalls, idempotencyKey }) {
    this.policyService.authorize(principal, 'mcp:run');
    requireString(idempotencyKey, 'idempotencyKey');
    requireArray(toolCalls, 'toolCalls');
    const prior = this.store.list('mcpRuns').find((run) => run.idempotencyKey === idempotencyKey);
    if (prior) return prior;
    const calculated = approvalHash(approval ?? { toolCalls, audience, scopes, principalId: principal.principalId });
    if (expectedApprovalHash && expectedApprovalHash !== calculated) throw new Error('MCP approval hash review required');
    const run = { runId: id('mcp-run'), idempotencyKey, principalId: principal.principalId, audience, scopes, approvalHash: expectedApprovalHash ?? calculated, status: 'RUNNING', toolCalls, cursor: 0, checkpoints: [], outputs: [], startedAt: nowIso(this.clock) };
    await this.store.put('mcpRuns', run.runId, run);
    await this.audit(run, 'RUN_STARTED');
    return run;
  }

  async execute(runId, { maxSteps = Infinity } = {}) {
    const run = this.store.get('mcpRuns', runId);
    if (!run) throw new Error('MCP run is required');
    let steps = 0;
    while (run.cursor < run.toolCalls.length && steps < maxSteps) {
      const call = run.toolCalls[run.cursor];
      const server = this.servers.get(call.serverId);
      const handler = server?.tools?.[call.tool];
      if (!handler) throw new Error(`MCP tool ${call.serverId}/${call.tool} is not registered`);
      const output = await handler(call.input ?? {}, { runId, principalId: run.principalId, approvalHash: run.approvalHash });
      run.outputs.push({ index: run.cursor, serverId: call.serverId, tool: call.tool, output, completedAt: nowIso(this.clock) });
      run.cursor += 1;
      run.checkpoints.push({ cursor: run.cursor, outputCount: run.outputs.length, at: nowIso(this.clock) });
      steps += 1;
      await this.store.put('mcpRuns', runId, run);
      await this.audit(run, 'CHECKPOINT', { cursor: run.cursor });
    }
    if (run.cursor >= run.toolCalls.length) {
      run.status = 'COMPLETED';
      run.completedAt = nowIso(this.clock);
      await this.store.put('mcpRuns', runId, run);
      await this.audit(run, 'RUN_COMPLETED');
    }
    return run;
  }

  async resume(runId) {
    const run = this.store.get('mcpRuns', runId);
    if (!run) throw new Error('MCP run is required');
    if (run.status === 'COMPLETED') return run;
    run.status = 'RESUMED';
    run.resumedAt = nowIso(this.clock);
    await this.store.put('mcpRuns', runId, run);
    await this.audit(run, 'RUN_RESUMED', { cursor: run.cursor });
    return this.execute(runId);
  }

  async audit(run, action, detail = {}) {
    const evidence = recordEnvelope({ type: 'MCP_AUDIT', runId: run.runId, principalId: run.principalId, action, cursor: run.cursor, detail }, this.clock);
    await this.store.append('receipts', evidence);
    return evidence;
  }
}
