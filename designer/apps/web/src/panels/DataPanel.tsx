import { useMemo, useRef, useState } from 'react';
import {
  generateMockRows,
  parseCsv,
  parseJsonRecords,
  sanitizeRows,
  suggestMapping,
  templateFields,
  validateBatch,
  type ValidationReport,
} from '@athos-label/engine';
import { useDesigner, useRows } from '../store';
import { appMeasurer } from '../lib/measure';
import { Check, Notice, Num, Section } from '../components/ui';

export function DataPanel() {
  const dataset = useDesigner((s) => s.dataset);
  const mapping = useDesigner((s) => s.mapping);
  const sanitize = useDesigner((s) => s.sanitize);
  const template = useDesigner((s) => s.template);
  const brand = useDesigner((s) => s.brand);
  const previewIndex = useDesigner((s) => s.previewIndex);
  const { setDataset, setMapping, setSanitize, setPreviewIndex, select } = useDesigner.getState();
  const rows = useRows();
  const [error, setError] = useState<string | null>(null);
  const [mock, setMock] = useState({ count: 50, seed: 42 });
  const [url, setUrl] = useState('');
  const [report, setReport] = useState<ValidationReport | null>(null);
  const file = useRef<HTMLInputElement>(null);

  const fields = templateFields(template);
  const headers = dataset?.headers ?? [];
  const cleaned = useMemo(() => (dataset && sanitize ? sanitizeRows(dataset.rows).changedByColumn : {}), [dataset, sanitize]);
  const changed = Object.values(cleaned).reduce((a, b) => a + b, 0);

  const load = (fn: () => ReturnType<typeof parseCsv>) => {
    try {
      const ds = fn();
      setDataset(ds);
      setMapping(suggestMapping(fields, ds.headers));
      setReport(null);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const fromFile = async (f: File) => {
    const text = await f.text();
    load(() => (/\.json$/i.test(f.name) || /^\s*[[{]/.test(text) ? parseJsonRecords(text, f.name) : parseCsv(text, f.name)));
  };

  const fromRest = async () => {
    try {
      const res = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json: unknown = await res.json();
      load(() => parseJsonRecords(json, new URL(url).host, 'rest'));
    } catch (e) {
      setError(`API: ${(e as Error).message}`);
    }
  };

  return (
    <>
      <Section title="Fonte de dados">
        <div className="row">
          <button onClick={() => file.current?.click()}>Abrir CSV / JSON…</button>
          <input
            ref={file}
            type="file"
            accept=".csv,.txt,.json,text/csv,application/json"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) void fromFile(f);
              e.target.value = '';
            }}
          />
        </div>
        <div className="row bottom">
          <Num label="Linhas fictícias" min={1} max={100000} value={mock.count} onCommit={(count) => setMock({ ...mock, count })} />
          <Num label="Semente" min={0} value={mock.seed} onCommit={(seed) => setMock({ ...mock, seed })} />
          <button onClick={() => load(() => generateMockRows(mock.count, mock.seed))}>Gerar</button>
        </div>
        <div className="row bottom">
          <label className="field wide">
            <span>API REST (GET que devolve JSON)</span>
            <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…/produtos" />
          </label>
          <button disabled={!/^https?:\/\//.test(url)} onClick={() => void fromRest()}>
            Buscar
          </button>
        </div>
        {error && <Notice kind="error">{error}</Notice>}
        {dataset && (
          <p className="muted">
            <strong>{dataset.name}</strong>: {dataset.rows.length.toLocaleString('pt-BR')} linhas, {dataset.headers.length} colunas.
          </p>
        )}
        <Check
          label={`Limpar dados antes do motor${changed ? ` (${changed} células ajustadas)` : ''}`}
          checked={sanitize}
          onChange={setSanitize}
          title="Remove espaços duplicados e caracteres de controle; 'null', '#N/A' viram vazio"
        />
      </Section>

      <Section
        title="Mapeamento de colunas"
        actions={
          <button className="link" onClick={() => setMapping({ ...mapping, ...suggestMapping(fields, headers) })}>
            Sugerir
          </button>
        }
      >
        {fields.length === 0 ? (
          <p className="muted">O template ainda não usa campos. Use {'{{CAMPO}}'} no conteúdo de um objeto.</p>
        ) : (
          <>
            <div className="chips">
              {headers.map((h) => (
                <span key={h} className="chip draggable" draggable onDragStart={(e) => e.dataTransfer.setData('text/plain', h)} title="Arraste até um campo">
                  {h}
                </span>
              ))}
            </div>
            <table className="mapping">
              <tbody>
                {fields.map((f) => {
                  const direct = headers.includes(f);
                  return (
                    <tr
                      key={f}
                      onDragOver={(e) => e.preventDefault()}
                      onDrop={(e) => {
                        e.preventDefault();
                        setMapping({ ...mapping, [f]: e.dataTransfer.getData('text/plain') });
                      }}
                    >
                      <td>
                        <code>{`{{${f}}}`}</code>
                      </td>
                      <td>←</td>
                      <td>
                        <select value={mapping[f] ?? ''} onChange={(e) => setMapping({ ...mapping, [f]: e.target.value })}>
                          <option value="">{direct ? `(coluna ${f})` : '— sem coluna —'}</option>
                          {headers.map((h) => (
                            <option key={h} value={h}>
                              {h}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>{mapping[f] || direct ? '✓' : <span className="bad" title="Campo sem dado: vai sair vazio">!</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </>
        )}
      </Section>

      <Section title="Sanity Check">
        <p className="muted">Roda o pipeline inteiro (dados, layout, códigos) numa amostra de 10% do lote (mínimo 50) antes de imprimir.</p>
        <button
          className="primary"
          disabled={rows.length === 0}
          onClick={() => setReport(validateBatch(template, rows, { dpi: template.dpi, brand, measurer: appMeasurer }))}
        >
          Validar lote
        </button>
        {report && (
          <div className="report">
            <Notice kind={report.ok ? (report.warnings ? 'warn' : 'ok') : 'error'}>
              {report.sampledRows} de {report.totalRows.toLocaleString('pt-BR')} linhas verificadas: {report.errors} erro(s), {report.warnings} alerta(s).
              {report.ok ? ' Pode imprimir.' : ' Corrija antes de imprimir.'}
            </Notice>
            <ul className="findings">
              {report.findings.map((f, i) => (
                <li key={i} className={f.severity}>
                  <span className="count">{f.count}×</span> {f.message}
                  {f.objectId && (
                    <button className="link" onClick={() => select([f.objectId!])}>
                      objeto
                    </button>
                  )}
                  {f.exampleRows.length > 0 && (
                    <span className="rows">
                      linha{f.exampleRows.length > 1 ? 's' : ''}{' '}
                      {f.exampleRows.map((r) => (
                        <button key={r} className="link" onClick={() => setPreviewIndex(r - 1)}>
                          {r}
                        </button>
                      ))}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
      </Section>

      {rows.length > 0 && (
        <Section title="Dados (após limpeza e mapeamento)">
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>#</th>
                  {Object.keys(rows[0]!).map((h) => (
                    <th key={h}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, 25).map((r, i) => (
                  <tr key={i} className={i === previewIndex ? 'active' : ''} onClick={() => setPreviewIndex(i)}>
                    <td>{i + 1}</td>
                    {Object.keys(rows[0]!).map((h) => (
                      <td key={h}>{r[h]}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {rows.length > 25 && <p className="muted">Mostrando 25 de {rows.length.toLocaleString('pt-BR')}. Clique numa linha para ver na etiqueta.</p>}
        </Section>
      )}
    </>
  );
}
