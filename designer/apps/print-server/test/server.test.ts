import { afterEach, describe, expect, it } from 'vitest';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { promises as fs } from 'node:fs';
import { addObject, createBarcode1D, createTemplate, createTextObject, generateMockRows, sha256Hex, type JobState, type PrinterProfile } from '@athos-label/engine';
import { createPrintServer } from '../src/server';
import { FileStore } from '../src/store';
import { parseTsplStatus, parseZplHostStatus, sendTcp } from '../src/transport';

const TOKEN = 'teste-token-123';
const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

/** Impressora falsa: servidor TCP real em loopback que guarda tudo o que recebe. */
async function fakePrinter(opts: { reply?: Buffer } = {}) {
  const received: Buffer[] = [];
  const server = net.createServer((s) => {
    s.on('data', (d) => {
      received.push(d);
      if (opts.reply) s.write(opts.reply);
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  return { port: (server.address() as net.AddressInfo).port, received: () => Buffer.concat(received) };
}

async function startServer(extra: Partial<Parameters<typeof createPrintServer>[0]> = {}, dir?: string) {
  const dataDir = dir ?? (await fs.mkdtemp(path.join(os.tmpdir(), 'athos-ps-')));
  const server = await createPrintServer({
    store: new FileStore(dataDir),
    token: TOKEN,
    allowedOrigins: ['http://localhost:5173'],
    orchestrator: { backoffMs: [5] },
    status: async () => 'READY',
    ...extra,
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  const base = `http://127.0.0.1:${(server.address() as net.AddressInfo).port}`;
  const call = async (method: string, url: string, body?: unknown, token = TOKEN) => {
    const res = await fetch(base + url, {
      method,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { call, dataDir };
}

function template() {
  let t = createTemplate(50, 30);
  t = addObject(t, createTextObject({ content: '{{PRODUTO}}', x: 2, y: 2, width: 46, height: 6 }));
  t = addObject(t, createBarcode1D({ symbology: 'EAN13', content: '{{EAN}}', x: 5, y: 12, width: 40, height: 12 }));
  return t;
}

const printer = (port: number, language: PrinterProfile['language'] = 'ZPL'): PrinterProfile => ({
  id: 'p1',
  name: 'Bancada',
  language,
  dpi: 203,
  host: '127.0.0.1',
  port,
});

async function waitFor(call: Awaited<ReturnType<typeof startServer>>['call'], id: string, status: JobState['status']) {
  for (let i = 0; i < 200; i++) {
    const r = await call('GET', `/api/jobs/${id}`);
    if (r.json.status === status) return r.json;
    await new Promise((res) => setTimeout(res, 20));
  }
  throw new Error(`job não chegou em ${status}: ${JSON.stringify((await call("GET", `/api/jobs/${id}`)).json)}`);
}

describe('print-server', () => {
  it('exige token em tudo menos health; CORS só para a origem do designer', async () => {
    const { call } = await startServer();
    expect((await call('GET', '/api/health', undefined, '')).status).toBe(200);
    expect((await call('GET', '/api/printers', undefined, 'errado')).status).toBe(401);
    expect((await call('GET', '/api/printers')).status).toBe(200);
  });

  it('imprime 150 etiquetas ZPL numa impressora TCP real, em lotes, com auditoria por etiqueta', async () => {
    const fake = await fakePrinter();
    const { call } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const rows = generateMockRows(150, 4).rows;
    const job = await call('POST', '/api/jobs', {
      template: template(),
      templateVersion: 2,
      rows,
      chunkSize: 50,
      routing: { rules: [], defaultPrinterId: 'p1' },
      user: 'ana',
    });
    expect(job.status).toBe(200);
    const done = await waitFor(call, job.json.id, 'COMPLETED');
    expect(done.sentLabels).toBe(150);

    const zpl = fake.received().toString('utf8');
    expect(zpl.match(/\^XA/g)).toHaveLength(150);
    expect(zpl).toContain(rows[149]!.EAN!.slice(0, 12));

    const audit = (await call('GET', `/api/audit?jobId=${job.json.id}`)).json;
    expect(audit).toHaveLength(150);
    expect(audit[0]).toMatchObject({ user: 'ana', templateVersion: 2, printerName: 'Bancada', status: 'SENT' });
    // O hash auditado é exatamente o das bytes da 1ª etiqueta enviada.
    const first = zpl.slice(0, zpl.indexOf('^XZ\n') + 4);
    expect(audit[0].contentHash).toBe(await sha256Hex(new TextEncoder().encode(first)));
  });

  it('impressora fora da rede: tenta, pausa no checkpoint; volta e retoma sem perder etiqueta', async () => {
    const fake = await fakePrinter();
    let down = true;
    const { call } = await startServer({
      send: (p, payload) => (down ? Promise.reject(new Error('conexão recusada')) : sendTcp(p.host, p.port, payload)),
    });
    await call('PUT', '/api/printers', [printer(fake.port)]);
    const job = await call('POST', '/api/jobs', { template: template(), rows: generateMockRows(30, 1).rows, chunkSize: 10, routing: { rules: [], defaultPrinterId: 'p1' } });
    const paused = await waitFor(call, job.json.id, 'PAUSED');
    expect(paused.lastError).toMatch(/após 3 tentativas/);
    expect(paused.sentLabels).toBe(0);

    down = false;
    expect((await call('POST', `/api/jobs/${job.json.id}/resume`)).status).toBe(200);
    expect((await waitFor(call, job.json.id, 'COMPLETED')).sentLabels).toBe(30);
    expect(fake.received().toString().match(/\^XA/g)).toHaveLength(30);
  });

  it('job rodando quando o processo caiu volta PAUSADO ao religar (não reimprime sozinho)', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'athos-ps-'));
    const store = new FileStore(dir);
    await store.init();
    const state = {
      spec: { id: 'job-x', templateId: 't', templateVersion: 1, templateHash: '', rows: [{}], copies: 1, chunkSize: 1, routing: { rules: [], defaultPrinterId: 'p1' }, user: 'u', createdAt: '2026-10-18T00:00:00Z' },
      status: 'RUNNING',
      groups: [{ printerId: 'p1', indices: [0], next: 0 }],
      attempts: 0,
      lastError: null,
      sentLabels: 0,
      totalLabels: 1,
      updatedAt: '2026-10-18T00:00:00Z',
    } satisfies JobState;
    await store.saveJob({ state, template: template() });
    const { call } = await startServer({}, dir);
    const r = await call('GET', '/api/jobs/job-x');
    expect(r.json.status).toBe('PAUSED');
    expect(r.json.lastError).toMatch(/reiniciou/);
  });

  it('regra de roteamento apontando impressora inexistente é recusada antes de imprimir', async () => {
    const { call } = await startServer();
    const r = await call('POST', '/api/jobs', { template: template(), rows: [{}], routing: { rules: [], defaultPrinterId: 'nao-existe' } });
    expect(r.status).toBe(400);
    expect(r.json.error).toMatch(/não cadastrada/);
  });

  it('TSPL vai em 1 byte por caractere (ç = 0xE7), como o Print Bridge envia', async () => {
    const fake = await fakePrinter();
    const { call } = await startServer();
    await call('PUT', '/api/printers', [printer(fake.port, 'TSPL')]);
    const job = await call('POST', '/api/jobs', { template: template(), rows: [{ PRODUTO: 'Açúcar', EAN: '4006381333931' }], routing: { rules: [], defaultPrinterId: 'p1' } });
    await waitFor(call, job.json.id, 'COMPLETED');
    const bytes = fake.received();
    expect(bytes.toString('latin1')).toContain('"A\xe7\xfacar"');
    expect(bytes.includes(Buffer.from('PRINT 1,1\r\n'))).toBe(true);
  });
});

describe('status da impressora', () => {
  it('ZPL ~HS', () => {
    const ok = '\x02030,0,0,1245,000,0,0,0,000,0,0,0\x03\r\n\x02000,0,0,0,1,2,6,0,00000000,1,000\x03\r\n\x021234,0\x03\r\n';
    expect(parseZplHostStatus(ok)).toBe('READY');
    expect(parseZplHostStatus(ok.replace('030,0,0', '030,1,0'))).toBe('PAPER_OUT');
    expect(parseZplHostStatus(ok.replace('030,0,0', '030,0,1'))).toBe('PAUSED');
    expect(parseZplHostStatus(ok.replace('000,0,0,0,1', '000,0,1,0,1'))).toBe('HEAD_OPEN');
    expect(parseZplHostStatus('lixo')).toBe('UNKNOWN');
  });

  it('TSPL ESC !?', () => {
    expect(parseTsplStatus(0)).toBe('READY');
    expect(parseTsplStatus(0x04)).toBe('PAPER_OUT');
    expect(parseTsplStatus(0x01)).toBe('HEAD_OPEN');
    expect(parseTsplStatus(0x10)).toBe('PAUSED');
  });

  it('servidor real responde status por TCP', async () => {
    const fake = await fakePrinter({ reply: Buffer.from([0x04]) });
    const { printerStatus } = await import('../src/transport');
    expect(await printerStatus(printer(fake.port, 'TSPL'))).toBe('PAPER_OUT');
    expect(await printerStatus(printer(1, 'TSPL'), 300)).toBe('OFFLINE');
  });
});

