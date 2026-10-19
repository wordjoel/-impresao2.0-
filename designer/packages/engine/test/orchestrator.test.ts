import { describe, expect, it } from 'vitest';
import type { PrinterProfile } from '../src/model/objects';
import {
  PrintOrchestrator,
  RoutingError,
  planJob,
  type AuditEntry,
  type JobState,
  type PrintJobSpec,
  type PrinterStatus,
} from '../src/print/orchestrator';
import { sha256Hex } from '../src/templates/versioning';

const printer = (id: string, name: string): PrinterProfile => ({ id, name, language: 'ZPL', dpi: 203, host: `10.0.0.${id.length}`, port: 9100 });
const printers = new Map([
  ['p1', printer('p1', 'Estação 1')],
  ['p2', printer('p2', 'Estação 2')],
]);

function spec(n = 250): PrintJobSpec {
  return {
    id: 'job-1',
    templateId: 'tpl',
    templateVersion: 3,
    templateHash: 'abc',
    rows: Array.from({ length: n }, (_, i) => ({ TIPO: i % 10 === 0 ? 'FRAGIL' : 'PRECO', N: String(i) })),
    copies: 1,
    chunkSize: 100,
    routing: { rules: [{ id: 'r1', when: 'TIPO = "FRAGIL"', printerId: 'p1' }], defaultPrinterId: 'p2' },
    user: 'ana',
    createdAt: '2026-10-18T10:00:00Z',
  };
}

function harness(opts: { fail?: (printerId: string, call: number) => boolean; status?: PrinterStatus } = {}) {
  const sends: { printer: string; labels: number }[] = [];
  const audit: AuditEntry[] = [];
  const states: JobState[] = [];
  let calls = 0;
  const enc = new TextEncoder();
  const orch = new PrintOrchestrator(
    {
      printers,
      renderLabel: (_p, _row, i, copies) => enc.encode(`L${i}x${copies}\n`),
      send: async (p, payload) => {
        calls++;
        if (opts.fail?.(p.id, calls)) throw new Error('conexão recusada');
        sends.push({ printer: p.id, labels: new TextDecoder().decode(payload).split('\n').length - 1 });
      },
      status: opts.status ? async () => opts.status! : undefined,
      saveState: async (s) => void states.push(s),
      audit: async (e) => void audit.push(...e),
      sleep: async () => {},
    },
    { maxAttempts: 3, backoffMs: [1] },
  );
  return { orch, sends, audit, states };
}

describe('roteamento', () => {
  it('regra por expressão manda FRAGIL para a Estação 1', () => {
    const s = planJob(spec(), printers);
    expect(s.groups.map((g) => [g.printerId, g.indices.length])).toEqual([
      ['p1', 25],
      ['p2', 225],
    ]);
  });

  it('impressora inexistente e regra inválida são erro antes de imprimir', () => {
    expect(() => planJob({ ...spec(), routing: { rules: [], defaultPrinterId: 'px' } }, printers)).toThrow(RoutingError);
    expect(() => planJob({ ...spec(), routing: { rules: [{ id: 'r', when: 'A >', printerId: 'p1' }], defaultPrinterId: 'p2' } }, printers)).toThrow(/Regra/);
  });
});

describe('orquestrador', () => {
  it('lotes de 100, checkpoint por lote e auditoria por etiqueta', async () => {
    const h = harness();
    const done = await h.orch.run(planJob(spec(), printers));
    expect(done.status).toBe('COMPLETED');
    expect(h.sends).toEqual([
      { printer: 'p1', labels: 25 },
      { printer: 'p2', labels: 100 },
      { printer: 'p2', labels: 100 },
      { printer: 'p2', labels: 25 },
    ]);
    expect(h.audit).toHaveLength(250);
    expect(new Set(h.audit.map((a) => a.rowIndex)).size).toBe(250);
    expect(h.audit[0]).toMatchObject({ printerName: 'Estação 1', user: 'ana', templateVersion: 3, status: 'SENT' });
    expect(h.audit[0]!.contentHash).toBe(await sha256Hex(new TextEncoder().encode('L0x1\n')));
  });

  it('falha na impressora: tenta 3x, pausa no checkpoint e retoma sem duplicar', async () => {
    let broken = true;
    // A 3ª chamada é o 2º lote da p2 (1ª = p1, 2ª = p2 lote 1).
    const h = harness({ fail: (id, call) => broken && id === 'p2' && call >= 3 });
    const paused = await h.orch.run(planJob(spec(), printers));
    expect(paused.status).toBe('PAUSED');
    expect(paused.lastError).toMatch(/após 3 tentativas/);
    expect(paused.groups[1]!.next).toBe(100);
    expect(h.audit).toHaveLength(125);

    broken = false;
    const done = await h.orch.run(paused);
    expect(done.status).toBe('COMPLETED');
    expect(h.audit).toHaveLength(250);
    expect(new Set(h.audit.map((a) => a.rowIndex)).size).toBe(250);
  });

  it('sem papel: pausa na hora, sem enviar e sem gastar tentativa', async () => {
    const h = harness({ status: 'PAPER_OUT' });
    const s = await h.orch.run(planJob(spec(), printers));
    expect(s.status).toBe('PAUSED');
    expect(s.lastError).toMatch(/sem papel/);
    expect(s.attempts).toBe(0);
    expect(h.sends).toEqual([]);
  });

  it('cancelamento para antes do próximo lote', async () => {
    const h = harness();
    h.orch.cancel('job-1');
    expect((await h.orch.run(planJob(spec(), printers))).status).toBe('CANCELLED');
    expect(h.sends).toEqual([]);
  });

  it('cada lote persiste o estado (base da retomada após queda do processo)', async () => {
    const h = harness();
    await h.orch.run(planJob(spec(), printers));
    expect(h.states.map((s) => s.sentLabels)).toEqual([0, 25, 125, 225, 250, 250]);
  });
});
