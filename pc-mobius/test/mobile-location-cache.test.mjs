import test from 'node:test';
import assert from 'node:assert/strict';
import { IntegrationApiGateway } from '../integration/src/api-gateway.mjs';

test('authorized portal locations cannot enter a shared cache', async () => {
  const principal={principalId:'cache-test-guardian',scopes:['portal:read']};
  const api=new IntegrationApiGateway({portalService:{},portalLocations:{read:async caller=>{
    assert.equal(caller,principal);
    return {deviceIds:[],observations:[],readExpiresAt:'2026-11-14T00:00:05.000Z'};
  }}});
  const response=await api.handle({method:'GET',path:'/v1/portal/locations',principal});
  assert.equal(response.status,200);
  assert.equal(response.headers['cache-control'],'no-store, private');
  assert.equal(response.headers.vary,'Authorization');
  assert.equal(response.headers['x-content-type-options'],'nosniff');
  assert.equal(response.headers['x-privacy-bubble-contract'],'privacy-bubble.integration.v1');
});
