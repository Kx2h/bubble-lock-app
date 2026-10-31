import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

export class IntegrationStore {
  constructor(path = null) {
    this.path = path;
    this.data = { consents: {}, grants: {}, policies: {}, events: {}, handovers: {}, flRounds: {}, mcpRuns: {}, receipts: [] };
    this.initialized = false;
    this.writeQueue = Promise.resolve();
  }

  async init() {
    if (this.initialized) return this;
    if (this.path) {
      try {
        this.data = JSON.parse(await readFile(this.path, 'utf8'));
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
        await mkdir(dirname(this.path), { recursive: true });
        await this.flush();
      }
    }
    this.initialized = true;
    return this;
  }

  async flush() {
    if (!this.path) return;
    this.writeQueue = this.writeQueue.then(() => writeFile(this.path, `${JSON.stringify(this.data, null, 2)}\n`, 'utf8'));
    await this.writeQueue;
  }

  collection(name) {
    if (!this.data[name]) this.data[name] = {};
    return this.data[name];
  }

  async put(collection, id, value) {
    await this.init();
    this.collection(collection)[id] = value;
    await this.flush();
    return value;
  }

  async append(collection, value) {
    await this.init();
    if (!Array.isArray(this.data[collection])) this.data[collection] = [];
    this.data[collection].push(value);
    await this.flush();
    return value;
  }

  get(collection, id) {
    return this.collection(collection)[id];
  }

  list(collection) {
    const value = this.data[collection];
    return Array.isArray(value) ? [...value] : Object.values(value ?? {});
  }

  snapshot() {
    return JSON.parse(JSON.stringify(this.data));
  }
}
