-- Privacy Bubble PC-MOBIUS-INTEGRATION persistence schema.
CREATE SCHEMA IF NOT EXISTS privacy_bubble;

CREATE TABLE IF NOT EXISTS privacy_bubble.principal_binding (
  principal_id TEXT PRIMARY KEY,
  issuer TEXT NOT NULL,
  audience TEXT NOT NULL,
  originator TEXT NOT NULL,
  mtls_fingerprint TEXT NOT NULL,
  scopes JSONB NOT NULL,
  bound_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.consent (
  consent_id TEXT PRIMARY KEY,
  subject_id TEXT NOT NULL,
  purpose TEXT NOT NULL,
  scopes JSONB NOT NULL,
  revision INTEGER NOT NULL,
  status TEXT NOT NULL,
  consent_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.grant_lease (
  grant_id TEXT PRIMARY KEY,
  consent_id TEXT NOT NULL REFERENCES privacy_bubble.consent(consent_id),
  subject_id TEXT NOT NULL,
  scopes JSONB NOT NULL,
  revision INTEGER NOT NULL,
  status TEXT NOT NULL,
  approval_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.mec_event (
  event_id TEXT PRIMARY KEY,
  service TEXT NOT NULL,
  event_type TEXT NOT NULL,
  ue_id TEXT NOT NULL,
  payload JSONB NOT NULL,
  status TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.handover_session (
  ue_id TEXT PRIMARY KEY,
  handover_id TEXT NOT NULL,
  edge_id TEXT NOT NULL,
  epoch BIGINT NOT NULL,
  state TEXT NOT NULL,
  subscriptions JSONB NOT NULL,
  security_context JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.fl_round (
  round_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL,
  state TEXT NOT NULL,
  consent_revision INTEGER NOT NULL,
  participants JSONB NOT NULL,
  updates JSONB NOT NULL,
  checkpoint JSONB NOT NULL,
  model_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.mcp_run (
  run_id TEXT PRIMARY KEY,
  idempotency_key TEXT UNIQUE NOT NULL,
  principal_id TEXT NOT NULL,
  approval_hash TEXT NOT NULL,
  status TEXT NOT NULL,
  cursor INTEGER NOT NULL,
  tool_calls JSONB NOT NULL,
  checkpoints JSONB NOT NULL,
  outputs JSONB NOT NULL,
  started_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS privacy_bubble.integration_audit (
  event_id TEXT PRIMARY KEY,
  run_id TEXT,
  action TEXT NOT NULL,
  principal_id TEXT,
  detail JSONB NOT NULL,
  receipt_hash TEXT,
  recorded_at TIMESTAMPTZ NOT NULL
);
