import { useCallback, useEffect, useMemo, useState } from 'react';
import { createId, validateBatch, type AuditEntry, type PrinterProfile, type PrinterStatus, type RoutingRule, type ValidationReport } from '@athos-label/engine';
import { useDesigner, useRows } from '../store';
import { PrintClient, type JobSummary } from '../lib/print-client';
import { appMeasurer } from '../lib/measure';
import { load, save } from '../lib/persist';
import { Check, Notice, Num, Section, Select } from '../components/ui';

const STATUS_TEXT: Record<PrinterStatus, string> = {
  READY: '🟢 pronta',
  PAPER_OUT: '🟠 sem papel',
  HEAD_OPEN: '🟠 cabeça aberta',
  PAUSED: '🟠 pausada',
  OFFLINE: '🔴 fora da rede',
  UNKNOWN: '⚪ sem resposta de status',
};
const JOB_TEXT: Record<JobSummary['status'], string> = {
  QUEUED: 'na fila',
  RUNNING: 'enviando',
  PAUSED: 'PAUSADO',
  COMPLETED: 'enviado',
  CANCELLED: 'cancelado',
};

/** Impressão em lote pelo print-server local: impressoras, roteamento, jobs e auditoria. */
export function PrintPanel() {
  const template = useDesigner((s) => s.template);
  const brand = useDesigner((s) => s.brand);
  const rows = useRows();
  const [cfg, setCfg] = useState(() => load('print', { url: 'http://127.0.0.1:7171', token: '', user: '' }));
  const client = useMemo(() => new PrintClient(cfg), [cfg]);
  const [connected, setConnected] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [printers, setPrinters] = useState<PrinterProfile[]>([]);
  const [status, setStatus] = useState<Record<string, PrinterStatus>>({});
  const [rules, setRules] = useState<RoutingRule[]>(() => load('routing', []));
  const [chosenDefault, setDefaultPrinter] = useState('');
  // A escolhida, se ainda existir; senão a primeira. Evita padrão vazio quando a impressora é
  // cadastrada depois de conectar (o seletor mostraria uma, o job iria sem nenhuma).
  const defaultPrinter = printers.some((x) => x.id === chosenDefault) ? chosenDefault : printers[0]?.id ?? '';
  const [copies, setCopies] = useState(1);
  const [chunk, setChunk] = useState(100);
  const [report, setReport] = useState<ValidationReport | null>(null);
  const [force, setForce] = useState(false);
  const [jobs, setJobs] = useState<JobSummary[]>([]);
  const [audit, setAudit] = useState<{ jobId: string; entries: AuditEntry[] } | null>(null);

  const run = async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    try {
      setError(null);
      return await fn();
    } catch (e) {
      setError((e as Error).message);
      return undefined;
    }
  };

  const connect = async () => {
    save('print', cfg);
    const list = await run(async () => {
      await client.health();
      return client.printers();
    });
    setConnected(!!list);
    if (list) {
      setPrinters(list);
      setDefaultPrinter((d) => d || list[0]?.id || '');
    }
  };

  const refreshJobs = useCallback(async () => {
    try {
      setJobs(await client.jobs());
    } catch {
      /* servidor caiu: o aviso aparece na próxima ação */
    }
  }, [client]);

  useEffect(() => {
    if (!connected) return;
    void refreshJobs();
    const t = setInterval(() => void refreshJobs(), 1500);
    return () => clearInterval(t);
  }, [connected, refreshJobs]);

  useEffect(() => {
    save('routing', rules);
  }, [rules]);

  const updatePrinter = (i: number, p: Partial<PrinterProfile>) => setPrinters(printers.map((x, k) => (k === i ? { ...x, ...p } : x)));

  const send = async () => {
    const r = validateBatch(template, rows, { dpi: template.dpi, brand, measurer: appMeasurer });
    setReport(r);
    if (!r.ok && !force) return;
    // A auditoria aponta a versão EXATA impressa: grava uma versão (ou reaproveita a última igual).
    await useDesigner.getState().saveVersion('Impressão em lote', cfg.user || 'anônimo');
    const last = useDesigner.getState().library[template.id]?.history.versions.at(-1);
    await run(() =>
      client.createJob({
        template,
        templateVersion: last?.version ?? 0,
        templateHash: last?.hash ?? '',
        rows,
        copies,
        chunkSize: chunk,
        routing: { rules, defaultPrinterId: defaultPrinter },
        user: cfg.user || 'anônimo',
      }),
    );
    setForce(false);
    void refreshJobs();
  };

  const printerOptions = printers.map((p) => [p.id, p.name] as const);

  return (
    <>
      <Section title="Servidor de impressão">
        <p className="muted">
          Rode <code>npm run print-server</code> na pasta <code>designer</code>. O token fica no arquivo indicado no terminal.
        </p>
        <div className="grid2">
          <label className="field">
            <span>Endereço</span>
            <input value={cfg.url} onChange={(e) => setCfg({ ...cfg, url: e.target.value })} />
          </label>
          <label className="field">
            <span>Token</span>
            <input type="password" autoComplete="off" value={cfg.token} onChange={(e) => setCfg({ ...cfg, token: e.target.value })} />
          </label>
          <label className="field">
            <span>Seu nome (auditoria)</span>
            <input value={cfg.user} onChange={(e) => setCfg({ ...cfg, user: e.target.value })} />
          </label>
        </div>
        <button onClick={() => void connect()}>Conectar</button>
        {connected && <Notice kind="ok">Conectado.</Notice>}
        {error && <Notice kind="error">{error}</Notice>}
      </Section>

      {connected && (
        <>
          <Section
            title="Impressoras"
            actions={
              <button
                className="link"
                onClick={() => setPrinters([...printers, { id: createId().slice(0, 8), name: `Estação ${printers.length + 1}`, language: 'TSPL', dpi: 203, host: '', port: 9100 }])}
              >
                + Adicionar
              </button>
            }
          >
            {printers.map((p, i) => (
              <div key={p.id} className="printer">
                <div className="grid2">
                  <label className="field">
                    <span>Nome</span>
                    <input value={p.name} onChange={(e) => updatePrinter(i, { name: e.target.value })} />
                  </label>
                  <Select
                    label="Linguagem"
                    value={p.language}
                    options={[
                      ['TSPL', 'TSPL (TSC)'],
                      ['ZPL', 'ZPL (Zebra)'],
                      ['EPL', 'EPL2'],
                    ]}
                    onChange={(language) => updatePrinter(i, { language })}
                  />
                  <label className="field">
                    <span>IP</span>
                    <input value={p.host} placeholder="10.10.40.32" onChange={(e) => updatePrinter(i, { host: e.target.value.trim() })} />
                  </label>
                  <Num label="Porta" min={1} max={65535} value={p.port} onCommit={(port) => updatePrinter(i, { port })} />
                  <Select
                    label="DPI"
                    value={p.dpi}
                    options={[
                      [203, '203'],
                      [300, '300'],
                      [600, '600'],
                    ]}
                    onChange={(dpi) => updatePrinter(i, { dpi })}
                  />
                </div>
                <div className="row">
                  <button onClick={() => void run(async () => setStatus({ ...status, [p.id]: (await client.status(p.id)).status }))}>Status</button>
                  {status[p.id] && <span>{STATUS_TEXT[status[p.id]!]}</span>}
                  <button className="link danger" onClick={() => setPrinters(printers.filter((_, k) => k !== i))}>
                    remover
                  </button>
                </div>
                {p.dpi !== template.dpi && <Notice kind="warn">O template está em {template.dpi} dpi; esta impressora é {p.dpi} dpi. O código de barras será recalculado para ela.</Notice>}
              </div>
            ))}
            <button className="primary" onClick={() => void run(async () => setPrinters(await client.savePrinters(printers)))}>
              Salvar impressoras
            </button>
          </Section>

          <Section title="Roteamento" actions={<button className="link" onClick={() => setRules([...rules, { id: createId(), when: 'TIPO = "FRAGIL"', printerId: printers[0]?.id ?? '' }])}>+ Regra</button>}>
            <p className="muted">A primeira regra verdadeira escolhe a impressora da etiqueta; sem regra, vai para a padrão.</p>
            {rules.map((r, i) => (
              <div key={r.id} className="row bottom">
                <label className="field wide">
                  <span>Se</span>
                  <input className="mono" value={r.when} onChange={(e) => setRules(rules.map((x, k) => (k === i ? { ...x, when: e.target.value } : x)))} />
                </label>
                <Select label="Imprimir em" value={r.printerId} options={printerOptions} onChange={(printerId) => setRules(rules.map((x, k) => (k === i ? { ...x, printerId } : x)))} />
                <button className="icon-btn" title="Remover regra" onClick={() => setRules(rules.filter((_, k) => k !== i))}>
                  ✕
                </button>
              </div>
            ))}
            {printers.length > 0 && <Select label="Padrão" value={defaultPrinter} options={printerOptions} onChange={(v) => setDefaultPrinter(String(v))} />}
          </Section>

          <Section title={`Imprimir "${template.name}"`}>
            <div className="grid2">
              <Num label="Cópias por etiqueta" min={1} max={999} value={copies} onCommit={setCopies} />
              <Num label="Etiquetas por lote" min={1} max={1000} value={chunk} onCommit={setChunk} hint="Checkpoint a cada lote" />
            </div>
            <button className="primary" disabled={!defaultPrinter || rows.length === 0} onClick={() => void send()}>
              Validar e imprimir {rows.length.toLocaleString('pt-BR')} etiqueta(s)
            </button>
            {report && !report.ok && (
              <>
                <Notice kind="error">
                  Sanity Check: {report.errors} erro(s) em {report.sampledRows} linhas verificadas ({report.findings[0]?.message}). Veja a aba Dados.
                </Notice>
                <Check label="Imprimir mesmo assim" checked={force} onChange={setForce} />
              </>
            )}
            <p className="muted">"Enviado" significa que a impressora aceitou os bytes, não que a etiqueta saiu.</p>
          </Section>

          <Section title="Jobs">
            {jobs.length === 0 && <p className="muted">Nenhum job ainda.</p>}
            <ul className="jobs">
              {jobs.slice(0, 20).map((j) => (
                <li key={j.id} className={j.status.toLowerCase()}>
                  <div className="row">
                    <strong>{JOB_TEXT[j.status]}</strong>
                    <span>
                      {j.sentLabels}/{j.totalLabels} · v{j.templateVersion} · {j.user} · {new Date(j.createdAt).toLocaleTimeString('pt-BR')}
                    </span>
                  </div>
                  <div className="progress">
                    <div style={{ width: `${(100 * j.sentLabels) / Math.max(1, j.totalLabels)}%` }} />
                  </div>
                  {j.lastError && <Notice kind={j.status === 'PAUSED' ? 'warn' : 'info'}>{j.lastError}</Notice>}
                  <div className="row">
                    {j.status === 'PAUSED' && <button onClick={() => void run(() => client.resume(j.id)).then(refreshJobs)}>Retomar</button>}
                    {(j.status === 'PAUSED' || j.status === 'RUNNING' || j.status === 'QUEUED') && (
                      <button className="danger" onClick={() => void run(() => client.cancel(j.id)).then(refreshJobs)}>
                        Cancelar
                      </button>
                    )}
                    <button className="link" onClick={() => void run(async () => setAudit({ jobId: j.id, entries: await client.audit(j.id) }))}>
                      auditoria
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </Section>

          {audit && (
            <Section title={`Auditoria (${audit.entries.length})`} actions={<button className="link" onClick={() => setAudit(null)}>fechar</button>}>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Linha</th>
                      <th>Impressora</th>
                      <th>Usuário</th>
                      <th>Versão</th>
                      <th>SHA-256 do conteúdo</th>
                      <th>Quando</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.entries.slice(0, 200).map((e) => (
                      <tr key={`${e.rowIndex}-${e.at}`}>
                        <td>{e.rowIndex + 1}</td>
                        <td>{e.printerName}</td>
                        <td>{e.user}</td>
                        <td>v{e.templateVersion}</td>
                        <td>
                          <code title={e.contentHash}>{e.contentHash.slice(0, 12)}…</code>
                        </td>
                        <td>{new Date(e.at).toLocaleString('pt-BR')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Section>
          )}
        </>
      )}
    </>
  );
}
