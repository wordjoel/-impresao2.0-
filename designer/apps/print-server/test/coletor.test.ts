import { afterEach, describe, expect, it } from 'vitest';
import { createHash, createPublicKey, randomUUID, verify } from 'node:crypto';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { promises as fs } from 'node:fs';
import {
  addObject,
  canonicalJson,
  createBarcode1D,
  createTemplate,
  createTextObject,
  sha256Hex,
  type LabelTemplate,
} from '@athos-label/engine';
import { assembleLabel, type LabelPackage } from '@athos-label/exporters';
import { ColetorService } from '../src/coletor';
import { createColetorLanServer, createPrintServer } from '../src/server';
import { FileStore } from '../src/store';

/** Passo 6b: publicação para coletores, de ponta a ponta nos dois listeners. */

const ADMIN = 'teste-token-admin';
const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  while (cleanups.length) await cleanups.pop()!();
});

async function listen(server: import('node:http').Server) {
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  cleanups.push(() => new Promise<void>((r) => server.close(() => r())));
  return `http://127.0.0.1:${(server.address() as net.AddressInfo).port}`;
}

async function start(dir?: string, serverUrl: string | null = 'http://10.10.40.10:7172') {
  const dataDir = dir ?? (await fs.mkdtemp(path.join(os.tmpdir(), 'athos-coletor-')));
  const store = new FileStore(dataDir);
  const coletor = new ColetorService(store, { serverUrl: serverUrl ?? undefined });
  const admin = await listen(await createPrintServer({ store, token: ADMIN, allowedOrigins: [], status: async () => 'READY', coletor }));
  const lan = await listen(createColetorLanServer({ coletor }));
  const call = async (base: string, token: string | null, method: string, url: string, body?: unknown, headers: Record<string, string> = {}) => {
    const res = await fetch(base + url, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    return { status: res.status, headers: res.headers, json: (text ? JSON.parse(text) : null) as any };
  };
  return {
    dataDir,
    admin: (method: string, url: string, body?: unknown) => call(admin, ADMIN, method, url, body),
    lan: (token: string | null, method: string, url: string, body?: unknown, headers?: Record<string, string>) => call(lan, token, method, url, body, headers),
    adminWith: (token: string, method: string, url: string) => call(admin, token, method, url),
  };
}

function template(): LabelTemplate {
  let t = createTemplate(60, 40, 'Gôndola');
  t = addObject(t, createTextObject({ id: 'lote', name: 'Lote', content: 'Lote {{LOTE}}', x: 2, y: 6, width: 50, height: 5, fit: 'NONE' }));
  return addObject(t, createBarcode1D({ id: 'ean', name: 'EAN', symbology: 'EAN13', content: '{{EAN}}', x: 5, y: 12, width: 40, height: 12 }));
}

async function publishBody(t: LabelTemplate, version = 1) {
  return { template: t, templateVersion: version, templateHash: await sha256Hex(canonicalJson(t)), targets: [{ language: 'TSPL', dpi: 203 }], user: 'ana' };
}

const report = (over: Record<string, unknown> = {}) => ({
  reportId: randomUUID(),
  templateId: 'x',
  templateVersion: 1,
  packageSha256: 'a'.repeat(64),
  target: { language: 'TSPL', dpi: 203 },
  printer: { name: 'MB241 balcão', model: 'TSC_MB241' },
  status: 'SENT',
  versionCheck: 'CONFIRMED',
  labels: [{ bytesSha256: 'b'.repeat(64) }],
  at: '2026-10-20T12:00:00Z',
  ...over,
});

describe('coletor: publicação e rede da loja', () => {
  it('publica, assina e o coletor baixa, confere e monta a etiqueta', async () => {
    const s = await start();
    const t = template();
    const pub = await s.admin('POST', '/api/coletor/publish', await publishBody(t, 2));
    expect(pub.status).toBe(201);
    expect(pub.json).toMatchObject({ templateId: t.id, name: 'Gôndola', templateVersion: 2, publishedBy: 'ana', targets: [{ language: 'TSPL', dpi: 203 }] });

    const dev = await s.admin('POST', '/api/coletor/devices', { name: 'CF550 balcão 2' });
    expect(dev.status).toBe(201);
    expect(dev.headers.get('cache-control')).toBe('no-store');
    expect(dev.json).toMatchObject({ serverUrl: 'http://10.10.40.10:7172', signingKey: { kid: expect.stringMatching(/^athos-label-/) } });
    const token = dev.json.token as string;

    expect((await s.lan(token, 'GET', '/coletor/v1/labels')).json.map((l: any) => l.templateId)).toEqual([t.id]);
    const got = await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}`);
    expect(got.status).toBe(200);
    const env = got.json;
    const bytes = Buffer.from(env.packageJson, 'utf8');
    // O que o app faz, na ordem do contrato: SHA-256, assinatura, vetores.
    expect(createHash('sha256').update(bytes).digest('hex')).toBe(env.packageSha256);
    expect(got.headers.get('etag')).toBe(`"${env.packageSha256}"`);
    expect(env.signature).toMatchObject({ alg: 'ES256', kid: dev.json.signingKey.kid });
    const pub_key = createPublicKey(dev.json.signingKey.publicKeyPem);
    expect(verify('sha256', bytes, { key: pub_key, dsaEncoding: 'der' }, Buffer.from(env.signature.value, 'base64'))).toBe(true);
    const tampered = Buffer.from(env.packageJson.replace('Gôndola', 'Gondola'), 'utf8');
    expect(verify('sha256', tampered, { key: pub_key, dsaEncoding: 'der' }, Buffer.from(env.signature.value, 'base64'))).toBe(false);
    const pkg = JSON.parse(env.packageJson) as LabelPackage;
    for (const v of pkg.targets[0]!.conformance) {
      expect(createHash('sha256').update(assembleLabel(pkg.targets[0]!, v.values, v.copies)).digest('hex')).toBe(v.bytesSha256);
    }

    expect((await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}`, undefined, { 'If-None-Match': `"${env.packageSha256}"` })).status).toBe(304);
    expect((await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}/version`)).json).toEqual({ templateId: t.id, templateVersion: 2, packageSha256: env.packageSha256 });

    // Nova versão: a conferência passa a apontar para outro pacote, e o ETag antigo não vale mais.
    const t3 = { ...t, name: 'Gôndola v3' };
    expect((await s.admin('POST', '/api/coletor/publish', await publishBody(t3, 3))).status).toBe(201);
    const v3 = (await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}/version`)).json;
    expect(v3.templateVersion).toBe(3);
    expect(v3.packageSha256).not.toBe(env.packageSha256);
    expect((await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}`, undefined, { 'If-None-Match': `"${env.packageSha256}"` })).status).toBe(200);

    // Despublicado: 404 na conferência, mesmo o app tendo o pacote.
    expect((await s.admin('DELETE', `/api/coletor/published/${t.id}`)).status).toBe(200);
    expect((await s.admin('DELETE', `/api/coletor/published/${t.id}`)).status).toBe(200);
    const gone = await s.lan(token, 'GET', `/coletor/v1/labels/${t.id}/version`);
    expect(gone.status).toBe(404);
    expect(gone.json).toMatchObject({ errorCode: 'LABEL_NOT_PUBLISHED' });
  });

  it('recusas da publicação: não portável (lista), hash, alvo', async () => {
    const s = await start();
    let t = template();
    t = addObject(t, createTextObject({ id: 'n', name: 'Contador', content: '{{SEQ(1,1,6)}}', fit: 'NONE' }));
    t = addObject(t, createTextObject({ id: 'w', name: 'Descrição', content: '{{DESC}}' }));
    const r = await s.admin('POST', '/api/coletor/publish', await publishBody(t));
    expect(r.status).toBe(422);
    expect(r.json.errorCode).toBe('TEMPLATE_NOT_PORTABLE');
    expect(r.json.extra.problems.map((p: any) => p.reason).sort()).toEqual(['SEQUENCE', 'TEXT_FIT']);

    const ok = template();
    const hash = await s.admin('POST', '/api/coletor/publish', { ...(await publishBody(ok)), templateHash: '0'.repeat(64) });
    expect([hash.status, hash.json.errorCode]).toEqual([400, 'TEMPLATE_HASH_MISMATCH']);
    const dpi = await s.admin('POST', '/api/coletor/publish', { ...(await publishBody(ok)), targets: [{ language: 'ZPL', dpi: 250 }] });
    expect([dpi.status, dpi.json.errorCode]).toEqual([400, 'INVALID_REQUEST']);
    const id = await s.admin('POST', '/api/coletor/publish', await publishBody({ ...ok, id: '../fora' }));
    expect(id.status).toBe(400);
    expect((await s.admin('GET', '/api/coletor/published')).json).toEqual([]);
  });

  it('separação dos listeners e dos tokens; revogação', async () => {
    const s = await start();
    const dev = await s.admin('POST', '/api/coletor/devices', { name: 'CF550 1' });
    const token = dev.json.token as string;

    const none = await s.lan(null, 'GET', '/coletor/v1/labels');
    expect([none.status, none.json.errorCode]).toEqual([401, 'DEVICE_NOT_AUTHORIZED']);
    expect((await s.lan(ADMIN, 'GET', '/coletor/v1/labels')).status).toBe(401); // token do PC não vale na LAN
    expect((await s.lan(token, 'GET', '/api/coletor/devices')).status).toBe(404); // LAN nunca atende administração
    expect((await s.lan(token, 'GET', '/api/health')).status).toBe(404);
    expect((await s.adminWith(token, 'GET', '/api/coletor/devices')).status).toBe(401); // token do coletor não vale no PC

    const list = (await s.admin('GET', '/api/coletor/devices')).json;
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ deviceId: dev.json.deviceId, name: 'CF550 1', revoked: false });
    expect(JSON.stringify(list)).not.toContain(token);
    expect(list[0].tokenSha256).toBeUndefined();
    expect(list[0].lastSeenAt).toBeNull();

    expect((await s.lan(token, 'GET', '/coletor/v1/labels')).status).toBe(200);
    expect((await s.admin('GET', '/api/coletor/devices')).json[0].lastSeenAt).not.toBeNull();
    // O arquivo guarda só o hash do token.
    const onDisk = await fs.readFile(path.join(s.dataDir, 'coletor', 'devices.json'), 'utf8');
    expect(onDisk).not.toContain(token);

    expect((await s.admin('DELETE', `/api/coletor/devices/${dev.json.deviceId}`)).status).toBe(200);
    expect((await s.admin('DELETE', `/api/coletor/devices/${dev.json.deviceId}`)).status).toBe(200);
    expect((await s.lan(token, 'GET', '/coletor/v1/labels')).status).toBe(401);
    expect((await s.admin('GET', '/api/coletor/devices')).json[0].revoked).toBe(true);

    expect((await s.admin('POST', '/api/coletor/devices', { name: '' })).status).toBe(400);
  });

  it('sem endereço do listener não cadastra coletor', async () => {
    const s = await start(undefined, null);
    const r = await s.admin('POST', '/api/coletor/devices', { name: 'CF550' });
    expect([r.status, r.json.errorCode]).toEqual([409, 'INVALID_REQUEST']);
  });

  it('relatório de impressão: idempotente por reportId e validado', async () => {
    const s = await start();
    const token = (await s.admin('POST', '/api/coletor/devices', { name: 'CF550' })).json.token as string;
    const r = report({ extraField: 'não vai para a trilha' });
    const first = await s.lan(token, 'POST', '/coletor/v1/print-reports', r);
    expect(first.json).toEqual({ reportId: r.reportId, duplicate: false });
    expect((await s.lan(token, 'POST', '/coletor/v1/print-reports', r)).json).toEqual({ reportId: r.reportId, duplicate: true });
    for (const bad of [report({ labels: [] }), report({ labels: [{ bytesSha256: 'xyz' }] }), report({ versionCheck: 'MAYBE' }), report({ reportId: 'nao-uuid' })]) {
      const res = await s.lan(token, 'POST', '/coletor/v1/print-reports', bad);
      expect([res.status, res.json.errorCode]).toEqual([400, 'INVALID_REQUEST']);
    }
    const lines = (await fs.readFile(path.join(s.dataDir, 'coletor', 'reports.jsonl'), 'utf8')).trim().split('\n');
    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0]!)).toMatchObject({ reportId: r.reportId, deviceName: 'CF550', versionCheck: 'CONFIRMED' });
    expect(lines[0]).not.toContain('extraField');
  });

  it('a chave de assinatura persiste no diretório de dados entre reinícios', async () => {
    const a = await start();
    const kid = (await a.admin('POST', '/api/coletor/devices', { name: 'A' })).json.signingKey.kid;
    const b = await start(a.dataDir);
    const again = (await b.admin('POST', '/api/coletor/devices', { name: 'B' })).json;
    expect(again.signingKey.kid).toBe(kid);
    expect(JSON.stringify(again)).not.toContain('PRIVATE KEY');
    expect(await fs.readFile(path.join(a.dataDir, 'coletor', 'signing-key.pem'), 'utf8')).toContain('PRIVATE KEY');
  });
});
