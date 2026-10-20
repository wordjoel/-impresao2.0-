import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import {
  createId,
  newSequenceCounter,
  planJob,
  PrintOrchestrator,
  reserveSequence,
  RoutingError,
  usesSequence,
  type SequenceCounter,
  type SequenceReset,
  type DataRow,
  type JobState,
  type LabelTemplate,
  type OrchestratorOptions,
  type PrinterProfile,
  type PrinterStatus,
  type Routing,
} from '@athos-label/engine';
import { compileLabel, type CompiledLabel } from '@athos-label/exporters';
import { FileStore, type JobRecord } from './store';
import { printerStatus, sendTcp } from './transport';

/**
 * Servidor local de impressão do designer. Só escuta em 127.0.0.1 e exige token (Bearer) em
 * toda rota além de /api/health: qualquer página aberta no navegador consegue falar com
 * localhost, então sem token um site qualquer poderia mandar imprimir.
 */

export interface ServerOptions {
  store: FileStore;
  token: string;
  allowedOrigins: string[];
  orchestrator?: Partial<OrchestratorOptions>;
  send?: (p: PrinterProfile, payload: Uint8Array) => Promise<void>;
  status?: (p: PrinterProfile) => Promise<PrinterStatus>;
  log?: (msg: string) => void;
}

interface JobRequest {
  template: LabelTemplate;
  templateVersion?: number;
  templateHash?: string;
  rows: DataRow[];
  copies?: number;
  chunkSize?: number;
  routing: Routing;
  user?: string;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const MAX_BODY = 50 * 1024 * 1024;

export async function createPrintServer(opts: ServerOptions): Promise<http.Server> {
  const { store } = opts;
  const log = opts.log ?? (() => {});
  await store.init();

  // Job que estava RODANDO quando o processo caiu: volta pausado, para o operador retomar
  // conscientemente (do checkpoint) em vez de reimprimir sozinho ao religar.
  for (const r of await store.jobs()) {
    if (r.state.status === 'RUNNING' || r.state.status === 'QUEUED') {
      await store.saveJob({ ...r, state: { ...r.state, status: 'PAUSED', lastError: 'Interrompido: o servidor reiniciou. Retome para continuar do último lote enviado.' } });
    }
  }

  const running = new Map<string, Promise<JobState>>();
  /** Último estado de cada job em andamento: consultas não precisam ler o arquivo. */
  const live = new Map<string, JobState>();
  const templates = new Map<string, LabelTemplate>();
  let printers = new Map((await store.printers()).map((p) => [p.id, p]));
  const compiled = new Map<string, CompiledLabel>();

  const orchestrator = new PrintOrchestrator(
    {
      get printers() {
        return printers;
      },
      renderLabel(printer, row, index, copies, seqOffset) {
        // O job em andamento define o template; um compilado por (job, impressora).
        const key = `${currentJob}|${printer.id}`;
        let c = compiled.get(key);
        if (!c) {
          c = compileLabel(templates.get(currentJob)!, { language: printer.language, dpi: printer.dpi });
          compiled.set(key, c);
        }
        return c.encode(c.render(row, index, copies, seqOffset).commands);
      },
      send: opts.send ?? ((p, payload) => sendTcp(p.host, p.port, payload)),
      status: opts.status ?? ((p) => printerStatus(p)),
      saveState: async (state) => {
        live.set(state.spec.id, state);
        await store.saveJob({ state, template: templates.get(state.spec.id)! });
      },
      audit: (entries) => store.appendAudit(entries),
      onEvent: (e) => log(`job ${e.state.spec.id}: ${e.type} ${e.state.sentLabels}/${e.state.totalLabels}${e.state.lastError ? ` (${e.state.lastError})` : ''}`),
    } as ConstructorParameters<typeof PrintOrchestrator>[0],
    { maxAttempts: 3, backoffMs: [2000, 5000, 10000], ...opts.orchestrator },
  );

  // Um job por vez: impressoras térmicas não intercalam etiquetas de jobs diferentes.
  let currentJob = '';
  let chain: Promise<unknown> = Promise.resolve();
  const start = (record: JobRecord) => {
    const id = record.state.spec.id;
    templates.set(id, record.template);
    live.set(id, { ...record.state, status: 'QUEUED' });
    const p: Promise<JobState> = chain.then(async () => {
      currentJob = id;
      try {
        return await orchestrator.run(record.state);
      } catch (e) {
        // Erro interno (disco, template corrompido...): o job para PAUSADO e com o motivo, nunca
        // fica "na fila" para sempre sem ninguém saber.
        log(`job ${id}: erro interno ${(e as Error).stack ?? e}`);
        const last = live.get(id) ?? record.state;
        const paused: JobState = { ...last, status: 'PAUSED', lastError: `Erro interno: ${(e as Error).message}`, updatedAt: new Date().toISOString() };
        await store.saveJob({ state: paused, template: record.template }).catch(() => {});
        return paused;
      } finally {
        [...compiled.keys()].filter((k) => k.startsWith(`${id}|`)).forEach((k) => compiled.delete(k));
        // Uma retomada pode já ter sido enfileirada: só limpa o que é desta execução.
        if (running.get(id) === p) {
          running.delete(id);
          live.delete(id);
        }
      }
    });
    chain = p.catch(() => {});
    running.set(record.state.spec.id, p);
  };

  // Contadores (numeração contínua): leitura-modificação-gravação sempre em fila, para dois
  // jobs criados ao mesmo tempo nunca receberem a mesma faixa.
  let counterLock: Promise<unknown> = Promise.resolve();
  const withCounters = <T>(fn: (c: Record<string, SequenceCounter>) => Promise<{ value: T; save: boolean }> | { value: T; save: boolean }) => {
    const p = counterLock.then(async () => {
      const all = await store.counters();
      const { value, save } = await fn(all);
      if (save) await store.saveCounters(all);
      return value;
    });
    counterLock = p.catch(() => {});
    return p;
  };

  const summary = (s: JobState) => ({
    id: s.spec.id,
    // Faixa de numeração reservada (SEQ); null se o template não numera.
    seqRange: s.spec.seqStart === undefined ? null : { first: s.spec.seqStart, last: s.spec.seqStart + s.spec.rows.length - 1 },
    status: s.status,
    sentLabels: s.sentLabels,
    totalLabels: s.totalLabels,
    lastError: s.lastError,
    attempts: s.attempts,
    user: s.spec.user,
    templateId: s.spec.templateId,
    templateVersion: s.spec.templateVersion,
    createdAt: s.spec.createdAt,
    updatedAt: s.updatedAt,
    groups: s.groups.map((g) => ({ printerId: g.printerId, total: g.indices.length, sent: g.next })),
  });

  const tokenBuf = Buffer.from(opts.token);
  const authorized = (req: http.IncomingMessage) => {
    const given = Buffer.from((req.headers.authorization ?? '').replace(/^Bearer\s+/i, ''));
    return given.length === tokenBuf.length && timingSafeEqual(given, tokenBuf);
  };

  async function body<T>(req: http.IncomingMessage): Promise<T> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const c of req) {
      size += (c as Buffer).length;
      if (size > MAX_BODY) throw new HttpError(413, 'Corpo maior que 50 MB');
      chunks.push(c as Buffer);
    }
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8')) as T;
    } catch {
      throw new HttpError(400, 'JSON inválido');
    }
  }

  async function route(req: http.IncomingMessage, url: URL): Promise<unknown> {
    const m = req.method ?? 'GET';
    const parts = url.pathname.split('/').filter(Boolean); // ['api', ...]
    if (parts[0] !== 'api') throw new HttpError(404, 'Rota não encontrada');
    const [, a, b, c] = parts;

    if (a === 'health' && m === 'GET') return { ok: true, service: 'athos-label print-server' };
    if (!authorized(req)) throw new HttpError(401, 'Token ausente ou inválido');

    if (a === 'printers' && !b && m === 'GET') return [...printers.values()];
    if (a === 'printers' && !b && m === 'PUT') {
      const list = await body<PrinterProfile[]>(req);
      if (!Array.isArray(list)) throw new HttpError(400, 'Esperado lista de impressoras');
      for (const p of list) {
        if (!p.id || !p.name || !p.host || !Number.isInteger(p.port) || !['ZPL', 'TSPL', 'EPL'].includes(p.language) || !(p.dpi > 0)) {
          throw new HttpError(400, `Impressora inválida: ${JSON.stringify(p).slice(0, 120)}`);
        }
      }
      await store.savePrinters(list);
      printers = new Map(list.map((p) => [p.id, p]));
      return list;
    }
    if (a === 'printers' && b && c === 'status' && m === 'GET') {
      const p = printers.get(b);
      if (!p) throw new HttpError(404, 'Impressora não cadastrada');
      return { status: await (opts.status ?? printerStatus)(p) };
    }

    if (a === 'jobs' && !b && m === 'POST') {
      const r = await body<JobRequest>(req);
      if (!r.template || !Array.isArray(r.template.objects) || !Array.isArray(r.rows) || !r.routing) {
        throw new HttpError(400, 'Job precisa de template, rows e routing');
      }
      const spec = {
        id: createId(),
        templateId: r.template.id,
        templateVersion: r.templateVersion ?? 0,
        templateHash: r.templateHash ?? '',
        rows: r.rows,
        copies: Math.max(1, Math.floor(r.copies ?? 1)),
        chunkSize: Math.min(1000, Math.max(1, Math.floor(r.chunkSize ?? 100))),
        routing: r.routing,
        user: String(r.user ?? 'desconhecido').slice(0, 120),
        createdAt: new Date().toISOString(),
      } as JobState['spec'];
      let state: JobState;
      try {
        // Valida o roteamento ANTES de reservar números: job recusado não consome faixa.
        state = planJob(spec, printers);
      } catch (e) {
        if (e instanceof RoutingError) throw new HttpError(400, e.message);
        throw e;
      }
      if (usesSequence(r.template)) {
        const seqStart = await withCounters((all) => {
          const res = reserveSequence(all[spec.templateId] ?? newSequenceCounter(), spec.rows.length);
          all[spec.templateId] = res.counter;
          return { value: res.start, save: true };
        });
        state = { ...state, spec: { ...state.spec, seqStart } };
      }
      const record = { state, template: r.template };
      await store.saveJob(record);
      start(record);
      return summary(state);
    }
    if (a === 'jobs' && !b && m === 'GET') return (await store.jobs()).map((r) => summary(live.get(r.state.spec.id) ?? r.state));
    if (a === 'jobs' && b && !c && m === 'GET' && live.has(b)) return summary(live.get(b)!);
    if (a === 'jobs' && b) {
      const r = await store.job(b);
      if (!r) throw new HttpError(404, 'Job não encontrado');
      if (!c && m === 'GET') return summary(r.state);
      if (c === 'resume' && m === 'POST') {
        // O estado em memória é o mais recente; o arquivo pode estar um lote atrás.
        const current = live.get(b) ?? r.state;
        if (current.status !== 'PAUSED') throw new HttpError(409, `Job está ${current.status}, só PAUSED pode ser retomado`);
        // Mesmo que a execução que pausou ainda esteja encerrando, a fila serializa: a retomada
        // começa depois dela, do checkpoint gravado.
        const record = { state: current, template: r.template };
        start(record);
        return summary({ ...current, status: 'QUEUED' });
      }
      if (c === 'cancel' && m === 'POST') {
        if (running.has(b)) orchestrator.cancel(b);
        else if (r.state.status === 'PAUSED' || r.state.status === 'QUEUED') {
          await store.saveJob({ ...r, state: { ...r.state, status: 'CANCELLED', updatedAt: new Date().toISOString() } });
        }
        return { ok: true };
      }
    }
    if (a === 'counters' && !b && m === 'GET') return store.counters();
    if (a === 'counters' && b && m === 'PUT') {
      // Ajuste manual: próximo número e regra de reinício. Mudança explícita, com token.
      const p = await body<{ next?: unknown; reset?: unknown }>(req);
      const next = p.next;
      const reset = p.reset;
      if (next !== undefined && !(Number.isInteger(next) && (next as number) >= 0)) throw new HttpError(400, 'next precisa ser inteiro >= 0');
      if (reset !== undefined && reset !== 'NEVER' && reset !== 'DAILY') throw new HttpError(400, 'reset precisa ser NEVER ou DAILY');
      return withCounters((all) => {
        const cur = all[b] ?? newSequenceCounter();
        // `next` manual vale para hoje: sem atualizar o dia, um contador DAILY de dia anterior
        // ignoraria o ajuste e recomeçaria em 0 na próxima reserva.
        all[b] = {
          ...cur,
          ...(next !== undefined ? { next: next as number, day: newSequenceCounter().day } : {}),
          ...(reset !== undefined ? { reset: reset as SequenceReset } : {}),
        };
        log(`contador ${b}: ${JSON.stringify(all[b])}`);
        return { value: all[b], save: true };
      });
    }
    if (a === 'audit' && m === 'GET') return store.audit(url.searchParams.get('jobId') ?? undefined);
    throw new HttpError(404, 'Rota não encontrada');
  }

  return http.createServer(async (req, res) => {
    const origin = req.headers.origin;
    if (origin && opts.allowedOrigins.includes(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, OPTIONS');
    }
    if (req.method === 'OPTIONS') {
      res.writeHead(origin && opts.allowedOrigins.includes(origin) ? 204 : 403).end();
      return;
    }
    try {
      const result = await route(req, new URL(req.url ?? '/', 'http://127.0.0.1'));
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(JSON.stringify(result));
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) log(`erro: ${(e as Error).stack ?? e}`);
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }).end(
        JSON.stringify({ error: status === 500 ? 'Erro interno' : (e as Error).message }),
      );
    }
  });
}
