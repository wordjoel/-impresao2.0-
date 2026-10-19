import { parseExpression, evaluate, type Node } from '../expr/expression';
import type { DataRow } from '../data/sources';
import type { PrinterProfile } from '../model/objects';
import { sha256Hex } from '../templates/versioning';

/**
 * Orquestrador de impressão em lote.
 *  - Roteamento: cada linha vai para a impressora da primeira regra verdadeira
 *    (ex.: `TIPO = "FRAGIL"` -> Estação 1), senão para a padrão.
 *  - Lotes de `chunkSize` etiquetas por envio (memória e socket sob controle em 10.000 etiquetas).
 *  - Checkpoint depois de cada lote: se a impressora falhar na etiqueta 50, o job pausa, avisa e,
 *    ao retomar, continua do lote onde parou.
 *  - Heartbeat: consulta o status antes de cada lote; sem papel / cabeça aberta pausa sem gastar
 *    tentativa.
 *  - Auditoria: uma entrada por etiqueta (impressora, usuário, versão do template, SHA-256 do
 *    conteúdo exato enviado).
 *
 * Garantia: "pelo menos uma vez" por lote. Um lote que falha no meio do envio é reenviado inteiro;
 * lotes menores reduzem possíveis duplicatas. E `SENT` NÃO é "impresso": significa que a
 * impressora aceitou os bytes (mesma regra do Print Bridge).
 */

export interface RoutingRule {
  id: string;
  /** Expressão sobre a linha, ex.: `TIPO = "FRAGIL"` ou `PESO > 10`. */
  when: string;
  printerId: string;
}

export interface Routing {
  rules: RoutingRule[];
  defaultPrinterId: string;
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';

export type PrinterStatus = 'READY' | 'PAPER_OUT' | 'HEAD_OPEN' | 'PAUSED' | 'OFFLINE' | 'UNKNOWN';

/** Status que exigem gente na impressora: pausa o job sem gastar tentativa. */
const BLOCKING: PrinterStatus[] = ['PAPER_OUT', 'HEAD_OPEN', 'PAUSED'];

export interface PrintJobSpec {
  id: string;
  templateId: string;
  templateVersion: number;
  templateHash: string;
  rows: DataRow[];
  copies: number;
  chunkSize: number;
  routing: Routing;
  user: string;
  createdAt: string;
}

export interface PrinterGroup {
  printerId: string;
  /** Índices (na lista `rows`) das etiquetas desta impressora, em ordem. */
  indices: number[];
  /** Checkpoint: posição em `indices` da próxima etiqueta a enviar. */
  next: number;
}

export interface JobState {
  spec: PrintJobSpec;
  status: JobStatus;
  groups: PrinterGroup[];
  attempts: number;
  lastError: string | null;
  sentLabels: number;
  totalLabels: number;
  updatedAt: string;
}

export interface AuditEntry {
  jobId: string;
  rowIndex: number;
  copies: number;
  printerId: string;
  printerName: string;
  printerAddress: string;
  user: string;
  templateId: string;
  templateVersion: number;
  templateHash: string;
  /** SHA-256 dos bytes desta etiqueta exatamente como foram à impressora. */
  contentHash: string;
  status: 'SENT';
  at: string;
}

export interface OrchestratorDeps {
  printers: ReadonlyMap<string, PrinterProfile>;
  /** Comandos de UMA etiqueta já com cópias, como bytes prontos para o socket. */
  renderLabel(printer: PrinterProfile, row: DataRow, index: number, copies: number): Uint8Array;
  send(printer: PrinterProfile, payload: Uint8Array): Promise<void>;
  status?(printer: PrinterProfile): Promise<PrinterStatus>;
  saveState(state: JobState): Promise<void>;
  audit(entries: AuditEntry[]): Promise<void>;
  onEvent?(event: { type: 'progress' | 'paused' | 'completed' | 'cancelled'; state: JobState }): void;
  now?(): Date;
  sleep?(ms: number): Promise<void>;
}

export interface OrchestratorOptions {
  /** Tentativas por lote antes de pausar o job. */
  maxAttempts: number;
  /** Espera entre tentativas (o último valor se repete). */
  backoffMs: number[];
}

export const DEFAULT_ORCHESTRATOR: OrchestratorOptions = { maxAttempts: 3, backoffMs: [1000, 5000, 15000] };

export class RoutingError extends Error {}

export function compileRouting(routing: Routing): (row: DataRow, index: number) => string {
  const compiled: { node: Node; rule: RoutingRule }[] = routing.rules.map((rule) => {
    try {
      return { node: parseExpression(rule.when), rule };
    } catch (e) {
      throw new RoutingError(`Regra "${rule.when}": ${(e as Error).message}`);
    }
  });
  const epoch = new Date(0);
  return (row, index) => {
    for (const { node, rule } of compiled) {
      try {
        const v = evaluate(node, { row, index, now: epoch });
        if (v === true || (typeof v === 'string' && v !== '') || (typeof v === 'number' && v !== 0)) return rule.printerId;
      } catch {
        // Regra que não se aplica à linha (ex.: campo não numérico) simplesmente não casa.
      }
    }
    return routing.defaultPrinterId;
  };
}

export function planJob(spec: PrintJobSpec, printers: ReadonlyMap<string, PrinterProfile>, now = new Date()): JobState {
  if (spec.rows.length === 0) throw new RoutingError('Job sem etiquetas');
  if (spec.chunkSize < 1) throw new RoutingError('chunkSize precisa ser >= 1');
  const route = compileRouting(spec.routing);
  const groups = new Map<string, PrinterGroup>();
  spec.rows.forEach((row, i) => {
    const printerId = route(row, i);
    if (!printers.has(printerId)) throw new RoutingError(`Linha ${i + 1}: impressora "${printerId}" não cadastrada`);
    const g = groups.get(printerId) ?? { printerId, indices: [], next: 0 };
    g.indices.push(i);
    groups.set(printerId, g);
  });
  return {
    spec,
    status: 'QUEUED',
    groups: [...groups.values()],
    attempts: 0,
    lastError: null,
    sentLabels: 0,
    totalLabels: spec.rows.length,
    updatedAt: now.toISOString(),
  };
}

export class PrintOrchestrator {
  private readonly cancelled = new Set<string>();

  constructor(
    private readonly deps: OrchestratorDeps,
    private readonly opts: OrchestratorOptions = DEFAULT_ORCHESTRATOR,
  ) {}

  /** Pede o cancelamento; o job para no fim do lote em andamento. */
  cancel(jobId: string): void {
    this.cancelled.add(jobId);
  }

  async run(initial: JobState): Promise<JobState> {
    const now = () => (this.deps.now ? this.deps.now() : new Date());
    const sleep = this.deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
    let state: JobState = { ...initial, status: 'RUNNING', lastError: null, groups: initial.groups.map((g) => ({ ...g })) };
    const persist = async (patch: Partial<JobState>, event?: 'progress' | 'paused' | 'completed' | 'cancelled') => {
      state = { ...state, ...patch, updatedAt: now().toISOString() };
      await this.deps.saveState(state);
      if (event) this.deps.onEvent?.({ type: event, state });
      return state;
    };
    await persist({});

    for (const group of state.groups) {
      const printer = this.deps.printers.get(group.printerId);
      if (!printer) return persist({ status: 'PAUSED', lastError: `Impressora ${group.printerId} não cadastrada` }, 'paused');

      while (group.next < group.indices.length) {
        if (this.cancelled.has(state.spec.id)) {
          this.cancelled.delete(state.spec.id);
          return persist({ status: 'CANCELLED' }, 'cancelled');
        }

        if (this.deps.status) {
          const st = await this.deps.status(printer).catch((): PrinterStatus => 'OFFLINE');
          if (BLOCKING.includes(st)) {
            return persist({ status: 'PAUSED', lastError: `${printer.name}: ${STATUS_TEXT[st]}` }, 'paused');
          }
        }

        const slice = group.indices.slice(group.next, group.next + state.spec.chunkSize);
        const labels = slice.map((i) => this.deps.renderLabel(printer, state.spec.rows[i]!, i, state.spec.copies));
        const payload = concat(labels);

        try {
          await this.deps.send(printer, payload);
        } catch (e) {
          const attempts = state.attempts + 1;
          const message = `${printer.name}: ${(e as Error).message}`;
          if (attempts >= this.opts.maxAttempts) {
            // Checkpoint intacto: ao retomar, este mesmo lote é reenviado.
            return persist({ status: 'PAUSED', attempts: 0, lastError: `${message} (após ${attempts} tentativas)` }, 'paused');
          }
          await persist({ attempts, lastError: message });
          await sleep(this.opts.backoffMs[Math.min(attempts - 1, this.opts.backoffMs.length - 1)] ?? 1000);
          continue;
        }

        const at = now().toISOString();
        const hashes = await Promise.all(labels.map((l) => sha256Hex(l)));
        await this.deps.audit(
          slice.map((rowIndex, k) => ({
            jobId: state.spec.id,
            rowIndex,
            copies: state.spec.copies,
            printerId: printer.id,
            printerName: printer.name,
            printerAddress: `${printer.host}:${printer.port}`,
            user: state.spec.user,
            templateId: state.spec.templateId,
            templateVersion: state.spec.templateVersion,
            templateHash: state.spec.templateHash,
            contentHash: hashes[k]!,
            status: 'SENT' as const,
            at,
          })),
        );
        group.next += slice.length;
        await persist({ attempts: 0, lastError: null, sentLabels: state.sentLabels + slice.length }, 'progress');
      }
    }
    return persist({ status: 'COMPLETED' }, 'completed');
  }
}

const STATUS_TEXT: Record<PrinterStatus, string> = {
  READY: 'pronta',
  PAPER_OUT: 'sem papel/etiqueta',
  HEAD_OPEN: 'cabeça aberta',
  PAUSED: 'pausada no painel',
  OFFLINE: 'fora da rede',
  UNKNOWN: 'status desconhecido',
};

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((s, p) => s + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}
