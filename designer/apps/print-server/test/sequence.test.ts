import { afterEach, describe, expect, it } from 'vitest';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { promises as fs } from 'node:fs';
import { addObject, createTemplate, createTextObject, type JobState, type LabelTemplate, type PrinterProfile } from '@athos-label/engine';
import { createPrintServer } from '../src/server';
import { FileStore } from '../src/store';
import { sendTcp } from '../src/transport';

/** Passo 4 (2026-10-19): numeração contínua entre lotes, de ponta a ponta no print-server. */

const TOKEN = 'teste-token-seq';
const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function fakePrinter() {
  const received: Buffer[] = [];
  const server = net.createServer((s) => s.on('data', (d) => received.push(d)));
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  return { port: (server.address() as net.AddressInfo).port, text: () => Buffer.concat(received).toString('utf8') };
}

async function startServer(extra: Partial<Parameters<typeof createPrintServer>[0]> = {}, dir?: string) {
  const dataDir = dir ?? (await fs.mkdtemp(path.join(os.tmpdir(), 'athos-seq-')));
  const server = await createPrintServer({
    store: new FileStore(dataDir),
    token: TOKEN,
    allowedOrigins: [],
    orchestrator: { backoffMs: [5] },
    status: async () => 'READY',
    ...extra,
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  const base = `http://127.0.0.1:${(server.address() as net.AddressInfo).port}`;
  const call = async (method: string, url: string, body?: unknown) => {
    const res = await fetch(base + url, {
      method,
      headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { call, dataDir };
}

async function waitFor(call: Awaited<ReturnType<typeof startServer>>['call'], id: string, status: JobState['status']) {
  for (let i = 0; i < 200; i++) {
    const r = await call('GET', `/api/jobs/${id}`);
    if (r.json.status === status) return r.json;
    await new Promise((res) => setTimeout(res, 20));
  }
  throw new Error(`job não chegou em ${status}`);
}

const seqTemplate = (): LabelTemplate =>
  addObject(createTemplate(50, 20), createTextObject({ content: 'N{{SEQ(1, 1, 6)}}', x: 1, y: 1, width: 48, height: 6, fit: 'NONE' }));

const printer = (port: number): PrinterProfile => ({ id: 'p1', name: 'Bancada', language: 'ZPL', dpi: 203, host: '127.0.0.1', port });
const rows = (n: number) => Array.from({ length: n }, () => ({}));
const numbers = (zpl: string) => [...zpl.matchAll(/N(\d{6})/g)].map((m) => m[1]);
const job = (template: LabelTemplate, n: number) => ({ template, rows: rows(n), chunkSize: 2, routing: { rules: [], defaultPrinterId: 'p1' } });

describe('numeração contínua', () => {
  it('dois lotes seguidos do mesmo template continuam a numeração; faixa no job e na auditoria', async () => {
    const fake = await fakePrinter();
    const { call } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const t = seqTemplate();

    const a = await call('POST', '/api/jobs', job(t, 3));
    expect(a.json.seqRange).toEqual({ first: 0, last: 2 });
    await waitFor(call, a.json.id, 'COMPLETED');
    const b = await call('POST', '/api/jobs', job(t, 2));
    expect(b.json.seqRange).toEqual({ first: 3, last: 4 });
    await waitFor(call, b.json.id, 'COMPLETED');

    expect(numbers(fake.text())).toEqual(['000001', '000002', '000003', '000004', '000005']);
    const audit = (await call('GET', `/api/audit?jobId=${b.json.id}`)).json;
    expect(audit.map((e: { seqIndex: number }) => e.seqIndex)).toEqual([3, 4]);
    expect((await call('GET', '/api/counters')).json[t.id].next).toBe(5);
  });

  it('lote interrompido retoma com os MESMOS números, sem pular nem repetir', async () => {
    const fake = await fakePrinter();
    let down = true;
    const { call } = await startServer({ send: (p, payload) => (down ? Promise.reject(new Error('recusada')) : sendTcp(p.host, p.port, payload)) });
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const t = seqTemplate();
    const j = await call('POST', '/api/jobs', job(t, 4));
    await waitFor(call, j.json.id, 'PAUSED');
    down = false;
    await call('POST', `/api/jobs/${j.json.id}/resume`);
    await waitFor(call, j.json.id, 'COMPLETED');
    expect(numbers(fake.text())).toEqual(['000001', '000002', '000003', '000004']);
    expect((await call('GET', '/api/counters')).json[t.id].next).toBe(4);
  });

  it('dois jobs criados juntos recebem faixas diferentes', async () => {
    const fake = await fakePrinter();
    const { call } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const t = seqTemplate();
    const [a, b] = await Promise.all([call('POST', '/api/jobs', job(t, 5)), call('POST', '/api/jobs', job(t, 5))]);
    const ranges = [a.json.seqRange.first, b.json.seqRange.first].sort((x, y) => x - y);
    expect(ranges).toEqual([0, 5]);
  });

  it('template sem SEQ não consome número; job recusado (impressora inexistente) também não', async () => {
    const fake = await fakePrinter();
    const { call } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const plain = addObject(createTemplate(50, 20), createTextObject({ content: 'fixo', x: 1, y: 1, width: 40, height: 6 }));
    const r = await call('POST', '/api/jobs', job(plain, 2));
    expect(r.json.seqRange).toBeNull();
    const t = seqTemplate();
    const bad = await call('POST', '/api/jobs', { ...job(t, 2), routing: { rules: [], defaultPrinterId: 'nao-existe' } });
    expect(bad.status).toBe(400);
    expect((await call('GET', '/api/counters')).json).toEqual({});
  });

  it('ajuste manual: define o próximo e a regra; valores inválidos recusados; persiste ao religar', async () => {
    const fake = await fakePrinter();
    const { call, dataDir } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const t = seqTemplate();
    expect((await call('PUT', `/api/counters/${t.id}`, { next: -1 })).status).toBe(400);
    expect((await call('PUT', `/api/counters/${t.id}`, { reset: 'MENSAL' })).status).toBe(400);
    expect((await call('PUT', `/api/counters/${t.id}`, { next: 999, reset: 'DAILY' })).json).toMatchObject({ next: 999, reset: 'DAILY' });

    const again = await startServer({}, dataDir);
    await again.call('PUT', '/api/printers', [printer(fake.port)]);
    const j = await again.call('POST', '/api/jobs', job(t, 1));
    expect(j.json.seqRange).toEqual({ first: 999, last: 999 });
    await waitFor(again.call, j.json.id, 'COMPLETED');
    expect(numbers(fake.text())).toEqual(['001000']);
  });
});
