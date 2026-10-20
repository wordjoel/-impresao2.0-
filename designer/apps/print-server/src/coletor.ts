import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomBytes, sign, timingSafeEqual, type KeyObject } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { createId } from '@athos-label/engine';
import { compileColetorPackage, NotPortableError, PackageInputError, type ColetorCompileInput, type TargetId } from '@athos-label/exporters';
import type { FileStore } from './store';

/**
 * Passo 6b: publicação de pacotes para o coletor (contrato docs/contratos/designer-coletor.openapi.yaml).
 *
 * Tudo fica no diretório de dados do print-server (fora do repositório):
 *   coletor/signing-key.pem   chave privada ECDSA P-256, gerada no primeiro uso, nunca sai do PC
 *   coletor/published/<id>.json   resumo + envelope assinado do pacote atual de cada template
 *   coletor/devices.json      coletores cadastrados, só com o SHA-256 do token
 *   coletor/reports.jsonl     relatórios de impressão enviados pelos coletores (append-only)
 * Um único serviço atende os dois listeners (administração e rede da loja), então gravações
 * concorrentes passam pela mesma fila.
 */

export class ColetorError extends Error {
  constructor(
    readonly status: number,
    readonly errorCode: 'TEMPLATE_NOT_PORTABLE' | 'TEMPLATE_HASH_MISMATCH' | 'INVALID_REQUEST' | 'DEVICE_NOT_AUTHORIZED' | 'LABEL_NOT_PUBLISHED',
    message: string,
    readonly extra?: Record<string, unknown>,
  ) {
    super(message);
  }
}

export interface LabelSummary {
  templateId: string;
  name: string;
  templateVersion: number;
  templateHash: string;
  packageSha256: string;
  publishedAt: string;
  publishedBy: string;
  targets: TargetId[];
}

export interface LabelPackageEnvelope {
  packageJson: string;
  packageSha256: string;
  signature: { alg: 'ES256'; kid: string; value: string };
}

interface Published {
  summary: LabelSummary;
  envelope: LabelPackageEnvelope;
}

interface DeviceRecord {
  deviceId: string;
  name: string;
  tokenSha256: string;
  createdAt: string;
  lastSeenAt: string | null;
  revoked: boolean;
}

export type DeviceSummary = Omit<DeviceRecord, 'tokenSha256'>;

export interface DeviceProvisioning {
  deviceId: string;
  token: string;
  serverUrl: string;
  signingKey: { kid: string; publicKeyPem: string };
}

export interface PublishRequest {
  template: ColetorCompileInput['template'];
  templateVersion: number;
  templateHash: string;
  targets: TargetId[];
  user?: string;
  samples?: Record<string, string>;
}

export interface ColetorServiceOptions {
  /** Endereço que o coletor usa para chegar ao PC (ex.: http://10.10.40.10:7172). Sem ele não cadastra coletor. */
  serverUrl?: string;
  now?: () => Date;
}

const sha256 = (data: string | Buffer) => createHash('sha256').update(data).digest('hex');
const SAFE_ID = /^[\w-]{1,128}$/;
const HEX64 = /^[0-9a-f]{64}$/;
/** lastSeenAt não precisa de precisão de segundo: grava no máximo a cada minuto por coletor. */
const SEEN_EVERY_MS = 60_000;

export class ColetorService {
  private readonly now: () => Date;
  private key: { privateKey: KeyObject; kid: string; publicKeyPem: string } | null = null;
  private lock: Promise<unknown> = Promise.resolve();
  private reportIds: Set<string> | null = null;

  constructor(
    private readonly store: FileStore,
    private readonly opts: ColetorServiceOptions = {},
  ) {
    this.now = opts.now ?? (() => new Date());
  }

  private file(...p: string[]) {
    return path.join(this.store.dir, 'coletor', ...p);
  }

  /** Leitura-modificação-gravação em fila (dois listeners, um processo). */
  private serial<T>(fn: () => Promise<T>): Promise<T> {
    const p = this.lock.then(fn);
    this.lock = p.catch(() => {});
    return p;
  }

  private async signingKey() {
    if (this.key) return this.key;
    const file = this.file('signing-key.pem');
    let pem: string;
    try {
      pem = await fs.readFile(file, 'utf8');
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e;
      await fs.mkdir(this.file(), { recursive: true });
      const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      pem = privateKey.export({ type: 'pkcs8', format: 'pem' }) as string;
      // 'wx': nunca sobrescreve uma chave que outro processo acabou de criar.
      await fs.writeFile(file, pem, { encoding: 'utf8', mode: 0o600, flag: 'wx' }).catch(async (err) => {
        if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;
        pem = await fs.readFile(file, 'utf8');
      });
    }
    const privateKey = createPrivateKey(pem);
    const publicKey = createPublicKey(privateKey);
    const der = publicKey.export({ type: 'spki', format: 'der' });
    this.key = {
      privateKey,
      kid: `athos-label-${sha256(der).slice(0, 16)}`,
      publicKeyPem: publicKey.export({ type: 'spki', format: 'pem' }) as string,
    };
    return this.key;
  }

  // ---------------------------------------------------------------- publicação (administração)

  async publish(r: PublishRequest): Promise<LabelSummary> {
    if (!r || typeof r !== 'object' || !r.template || !Array.isArray(r.template.objects) || !Array.isArray(r.targets)) {
      throw new ColetorError(400, 'INVALID_REQUEST', 'Publicação precisa de template, templateVersion, templateHash e targets');
    }
    if (!SAFE_ID.test(String(r.template.id))) throw new ColetorError(400, 'INVALID_REQUEST', 'templateId inválido');
    if (typeof r.templateHash !== 'string' || !HEX64.test(r.templateHash)) throw new ColetorError(400, 'INVALID_REQUEST', 'templateHash precisa ser SHA-256 em hexa');
    let pkg;
    try {
      pkg = await compileColetorPackage({
        template: r.template,
        templateVersion: r.templateVersion,
        templateHash: r.templateHash,
        targets: r.targets,
        samples: r.samples,
        now: this.now(),
      });
    } catch (e) {
      if (e instanceof NotPortableError) throw new ColetorError(422, 'TEMPLATE_NOT_PORTABLE', e.message, { problems: e.problems });
      if (e instanceof PackageInputError) {
        throw new ColetorError(400, e.message.includes('templateHash') ? 'TEMPLATE_HASH_MISMATCH' : 'INVALID_REQUEST', e.message);
      }
      throw e;
    }
    const key = await this.signingKey();
    const packageJson = JSON.stringify(pkg);
    const bytes = Buffer.from(packageJson, 'utf8');
    const envelope: LabelPackageEnvelope = {
      packageJson,
      packageSha256: sha256(bytes),
      signature: { alg: 'ES256', kid: key.kid, value: sign('sha256', bytes, { key: key.privateKey, dsaEncoding: 'der' }).toString('base64') },
    };
    const summary: LabelSummary = {
      templateId: pkg.templateId,
      name: pkg.name,
      templateVersion: pkg.templateVersion,
      templateHash: pkg.templateHash,
      packageSha256: envelope.packageSha256,
      publishedAt: pkg.compiledAt,
      publishedBy: String(r.user ?? 'desconhecido').slice(0, 120),
      targets: pkg.targets.map((t) => ({ language: t.language, dpi: t.dpi })),
    };
    await this.serial(async () => {
      await fs.mkdir(this.file('published'), { recursive: true });
      await this.store.writeAtomic(this.file('published', `${summary.templateId}.json`), JSON.stringify({ summary, envelope } satisfies Published));
    });
    return summary;
  }

  private async published(templateId: string): Promise<Published | null> {
    if (!SAFE_ID.test(templateId)) return null;
    return this.store.readJson<Published | null>(this.file('published', `${templateId}.json`), null);
  }

  async listPublished(): Promise<LabelSummary[]> {
    let names: string[];
    try {
      names = await fs.readdir(this.file('published'));
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return [];
      throw e;
    }
    const out: LabelSummary[] = [];
    for (const n of names.filter((n) => n.endsWith('.json'))) {
      const p = await this.published(n.slice(0, -5));
      if (p) out.push(p.summary);
    }
    return out.sort((a, b) => a.name.localeCompare(b.name));
  }

  async unpublish(templateId: string): Promise<void> {
    if (!SAFE_ID.test(templateId)) throw new ColetorError(400, 'INVALID_REQUEST', 'templateId inválido');
    await this.serial(() => fs.rm(this.file('published', `${templateId}.json`), { force: true }));
  }

  // ---------------------------------------------------------------- coletores (administração)

  private devices(): Promise<DeviceRecord[]> {
    return this.store.readJson<DeviceRecord[]>(this.file('devices.json'), []);
  }

  private async saveDevices(list: DeviceRecord[]): Promise<void> {
    await fs.mkdir(this.file(), { recursive: true });
    await this.store.writeAtomic(this.file('devices.json'), JSON.stringify(list, null, 2));
  }

  async registerDevice(name: unknown): Promise<DeviceProvisioning> {
    const n = typeof name === 'string' ? name.trim() : '';
    if (n === '' || n.length > 60) throw new ColetorError(400, 'INVALID_REQUEST', 'Nome do coletor obrigatório (até 60 caracteres)');
    if (!this.opts.serverUrl) {
      throw new ColetorError(409, 'INVALID_REQUEST', 'Listener do coletor desligado: configure COLETOR_LISTEN (e COLETOR_URL) no print-server antes de cadastrar');
    }
    const key = await this.signingKey();
    const token = randomBytes(32).toString('base64url');
    const record: DeviceRecord = { deviceId: createId(), name: n, tokenSha256: sha256(token), createdAt: this.now().toISOString(), lastSeenAt: null, revoked: false };
    await this.serial(async () => this.saveDevices([...(await this.devices()), record]));
    return { deviceId: record.deviceId, token, serverUrl: this.opts.serverUrl, signingKey: { kid: key.kid, publicKeyPem: key.publicKeyPem } };
  }

  async listDevices(): Promise<DeviceSummary[]> {
    return (await this.devices()).map(({ tokenSha256: _, ...d }) => d);
  }

  async revokeDevice(deviceId: string): Promise<void> {
    await this.serial(async () => {
      const list = await this.devices();
      const d = list.find((x) => x.deviceId === deviceId);
      if (!d || d.revoked) return;
      d.revoked = true;
      await this.saveDevices(list);
    });
  }

  // ---------------------------------------------------------------- rede da loja (coletor)

  /** Token do coletor → aparelho ativo. Comparação em tempo constante contra o SHA-256 guardado. */
  async authenticate(authorization: string | undefined): Promise<DeviceSummary> {
    const token = (authorization ?? '').replace(/^Bearer\s+/i, '');
    const given = Buffer.from(sha256(token), 'hex');
    const list = await this.devices();
    let found: DeviceRecord | undefined;
    for (const d of list) {
      if (timingSafeEqual(given, Buffer.from(d.tokenSha256, 'hex')) && !d.revoked) found = d;
    }
    if (token === '' || !found) throw new ColetorError(401, 'DEVICE_NOT_AUTHORIZED', 'Coletor não autorizado');
    const now = this.now();
    if (!found.lastSeenAt || now.getTime() - Date.parse(found.lastSeenAt) >= SEEN_EVERY_MS) {
      const id = found.deviceId;
      await this.serial(async () => {
        const fresh = await this.devices();
        const d = fresh.find((x) => x.deviceId === id);
        if (d) {
          d.lastSeenAt = now.toISOString();
          await this.saveDevices(fresh);
        }
      });
    }
    const { tokenSha256: _, ...summary } = found;
    return summary;
  }

  async envelope(templateId: string): Promise<LabelPackageEnvelope> {
    const p = await this.published(templateId);
    if (!p) throw new ColetorError(404, 'LABEL_NOT_PUBLISHED', 'Template não publicado para coletores');
    return p.envelope;
  }

  async version(templateId: string): Promise<{ templateId: string; templateVersion: number; packageSha256: string }> {
    const p = await this.published(templateId);
    if (!p) throw new ColetorError(404, 'LABEL_NOT_PUBLISHED', 'Template não publicado para coletores');
    return { templateId: p.summary.templateId, templateVersion: p.summary.templateVersion, packageSha256: p.summary.packageSha256 };
  }

  /** Relatório best-effort do coletor. Idempotente por reportId. */
  async report(device: DeviceSummary, r: unknown): Promise<{ reportId: string; duplicate: boolean }> {
    const bad = (why: string) => new ColetorError(400, 'INVALID_REQUEST', `Relatório inválido: ${why}`);
    const o = r as Record<string, unknown>;
    if (!o || typeof o !== 'object') throw bad('corpo');
    if (typeof o.reportId !== 'string' || !/^[0-9a-f-]{36}$/i.test(o.reportId)) throw bad('reportId precisa ser UUID');
    for (const k of ['templateId', 'packageSha256', 'at'] as const) if (typeof o[k] !== 'string') throw bad(k);
    if (!Number.isInteger(o.templateVersion)) throw bad('templateVersion');
    if (!HEX64.test(o.packageSha256 as string)) throw bad('packageSha256');
    if (o.status !== 'SENT' && o.status !== 'FAILED') throw bad('status');
    if (o.versionCheck !== 'CONFIRMED' && o.versionCheck !== 'OFFLINE_GRACE') throw bad('versionCheck');
    const t = o.target as Record<string, unknown> | undefined;
    if (!t || (t.language !== 'ZPL' && t.language !== 'TSPL') || !Number.isInteger(t.dpi)) throw bad('target');
    const printer = o.printer as Record<string, unknown> | undefined;
    if (!printer || typeof printer.name !== 'string') throw bad('printer');
    const labels = o.labels;
    if (!Array.isArray(labels) || labels.length === 0) throw bad('sem etiquetas');
    if (!labels.every((l) => l && typeof l === 'object' && typeof l.bytesSha256 === 'string' && HEX64.test(l.bytesSha256))) throw bad('bytesSha256 fora do formato');

    const reportId = o.reportId.toLowerCase();
    return this.serial(async () => {
      if (!this.reportIds) {
        this.reportIds = new Set();
        try {
          const text = await fs.readFile(this.file('reports.jsonl'), 'utf8');
          for (const line of text.split('\n').filter(Boolean)) this.reportIds.add((JSON.parse(line) as { reportId: string }).reportId);
        } catch (e) {
          if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e;
        }
      }
      if (this.reportIds.has(reportId)) return { reportId, duplicate: true };
      await fs.mkdir(this.file(), { recursive: true });
      // Só os campos do contrato: nada que o coletor mande a mais vai para a trilha.
      const entry = {
        reportId,
        deviceId: device.deviceId,
        deviceName: device.name,
        receivedAt: this.now().toISOString(),
        templateId: o.templateId,
        templateVersion: o.templateVersion,
        packageSha256: o.packageSha256,
        target: { language: t.language, dpi: t.dpi },
        printer: { name: String(printer.name).slice(0, 120), model: typeof printer.model === 'string' ? printer.model.slice(0, 60) : undefined },
        operator: typeof o.operator === 'string' ? o.operator.slice(0, 120) : null,
        status: o.status,
        errorMessage: typeof o.errorMessage === 'string' ? o.errorMessage.slice(0, 500) : null,
        versionCheck: o.versionCheck,
        labels: labels.map((l: { bytesSha256: string }) => ({ bytesSha256: l.bytesSha256 })),
        at: o.at,
      };
      await fs.appendFile(this.file('reports.jsonl'), JSON.stringify(entry) + '\n', 'utf8');
      this.reportIds.add(reportId);
      return { reportId, duplicate: false };
    });
  }
}
