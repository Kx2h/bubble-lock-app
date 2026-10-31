import { recordEnvelope } from './integration-utils.mjs';

function jsonResponse(status, body) {
  return { status, headers: { 'content-type': 'application/json', 'x-privacy-bubble-contract': 'privacy-bubble.integration.v1' }, body };
}

export class IntegrationApiGateway {
  constructor({ policyService, mecIngress, handoverCoordinator, flCoordinator, mcpOrchestrator, clock = () => new Date().toISOString() } = {}) {
    Object.assign(this, { policyService, mecIngress, handoverCoordinator, flCoordinator, mcpOrchestrator, clock });
  }

  principalFrom(request) {
    if (request.principal) return request.principal;
    if (request.body?.principal) return request.body.principal;
    if (request.body?.oidcToken && request.body?.mtlsPeer) return this.policyService.bindPrincipal({ oidcToken: request.body.oidcToken, mtlsPeer: request.body.mtlsPeer, originator: request.body.originator });
    const headers = request.headers ?? {};
    return this.policyService.bindPrincipal({ oidcToken: headers.authorization, mtlsPeer: { verified: headers['x-mtls-verified'] === 'true', fingerprint: headers['x-mtls-fingerprint'], subject: headers['x-mtls-subject'] }, originator: headers['x-m2m-origin' ] });
  }

  async handle(request) {
    const method = String(request.method ?? 'GET').toUpperCase();
    const path = request.path ?? '/';
    try {
      if (method === 'POST' && path === '/v1/auth/bind') return jsonResponse(200, this.principalFrom(request));
      const principal = this.principalFrom(request);
      if (method === 'POST' && path === '/v1/consents') return jsonResponse(201, await this.policyService.createConsent({ principal, ...request.body }));
      if (method === 'POST' && path === '/v1/grants') return jsonResponse(201, await this.policyService.createGrant({ principal, ...request.body }));
      if (method === 'POST' && path === '/v1/grants/revoke') return jsonResponse(200, await this.policyService.revokeGrant({ principal, ...request.body }));
      if (method === 'POST' && path === '/v1/policies/approve') return jsonResponse(201, await this.policyService.approvePolicy({ principal, ...request.body }));
      if (method === 'POST' && path === '/v1/mec/callback') return jsonResponse(202, await this.mecIngress.ingest(request.body));
      if (method === 'POST' && path === '/v1/handover') return jsonResponse(202, await this.handoverCoordinator.handover(request.body));
      if (method === 'POST' && path === '/v1/fl/rounds') return jsonResponse(201, await this.flCoordinator.createRound(request.body));
      if (method === 'POST' && path === '/v1/fl/updates') return jsonResponse(202, await this.flCoordinator.submitUpdate(request.body));
      if (method === 'POST' && path === '/v1/fl/finalize') return jsonResponse(200, await this.flCoordinator.finalizeRound(request.body.roundId));
      if (method === 'POST' && path === '/v1/mcp/runs') return jsonResponse(201, await this.mcpOrchestrator.startRun({ principal, ...request.body }));
      if (method === 'POST' && path === '/v1/mcp/execute') return jsonResponse(200, await this.mcpOrchestrator.execute(request.body.runId, request.body.options));
      if (method === 'POST' && path === '/v1/mcp/resume') return jsonResponse(200, await this.mcpOrchestrator.resume(request.body.runId));
      if (method === 'GET' && path === '/v1/mcp/servers') return jsonResponse(200, this.mcpOrchestrator.listServers());
      return jsonResponse(404, recordEnvelope({ type: 'API_ROUTE_REVIEW', method, path }, this.clock));
    } catch (error) {
      return jsonResponse(403, recordEnvelope({ type: 'API_REVIEW', method, path, error: error.message }, this.clock));
    }
  }
}
