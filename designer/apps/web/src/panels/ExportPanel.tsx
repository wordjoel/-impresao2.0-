import { useMemo, useState } from 'react';
import { resolveLabel, type Issue, type PrinterLanguage } from '@athos-label/engine';
import { GENERATORS, labelToCommands, labelToSvg, labelsToPdf } from '@athos-label/exporters';
import { useDesigner, usePreviewRow, useRows } from '../store';
import { appMeasurer, rasterizeText } from '../lib/measure';
import { downloadBlob } from '../lib/persist';
import type { ExportMessage, ExportRequest } from '../lib/export.worker';
import { Check, Notice, Num, Section, Select, slug } from '../components/ui';

type Format = PrinterLanguage | 'SVG' | 'PDF';
const EXT: Record<Format, [string, string]> = {
  ZPL: ['zpl', 'text/plain'],
  TSPL: ['prn', 'application/octet-stream'],
  EPL: ['epl', 'application/octet-stream'],
  SVG: ['svg', 'image/svg+xml'],
  PDF: ['pdf', 'application/pdf'],
};

/** Mostra comandos com bytes binários (BITMAP/GW) legíveis: caractere de controle vira "·". */
// eslint-disable-next-line no-control-regex
const printable = (s: string) => s.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F]/g, '·');

export function ExportPanel() {
  const template = useDesigner((s) => s.template);
  const brand = useDesigner((s) => s.brand);
  const index = useDesigner((s) => s.previewIndex);
  const language = useDesigner((s) => s.language);
  const row = usePreviewRow();
  const rows = useRows();
  const [format, setFormat] = useState<Format>(language);
  const [copies, setCopies] = useState(1);
  const [textAsImage, setTextAsImage] = useState(false);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [result, setResult] = useState<{ kind: 'ok' | 'error'; text: string; issues?: Issue[] } | null>(null);
  const printer = format !== 'SVG' && format !== 'PDF';

  const preview = useMemo(() => {
    if (!printer) return null;
    const label = resolveLabel(template, row, { dpi: template.dpi, brand, measurer: appMeasurer, index });
    return labelToCommands(GENERATORS[format], label, { copies, rasterizeText: textAsImage ? rasterizeText : undefined });
  }, [printer, format, template, row, brand, index, copies, textAsImage]);

  const filename = (all: boolean) => `${slug(template.name)}${all ? `-${rows.length}` : `-linha${index + 1}`}.${EXT[format][0]}`;

  const exportCurrent = async () => {
    const label = resolveLabel(template, row, { dpi: template.dpi, brand, measurer: appMeasurer, index });
    if (format === 'SVG') downloadBlob(labelToSvg(label), filename(false), EXT.SVG[1]);
    else if (format === 'PDF') downloadBlob(await labelsToPdf([label], template.name), filename(false), EXT.PDF[1]);
    else if (preview) downloadBlob(GENERATORS[format].encode(preview.commands), filename(false), EXT[format][1]);
    setResult({ kind: 'ok', text: `${filename(false)} baixado.` });
  };

  const exportAll = () => {
    if (format === 'SVG') return;
    setResult(null);
    setProgress({ done: 0, total: rows.length });
    const worker = new Worker(new URL('../lib/export.worker.ts', import.meta.url), { type: 'module' });
    const started = performance.now();
    worker.onmessage = (e: MessageEvent<ExportMessage>) => {
      const m = e.data;
      if (m.type === 'progress') setProgress({ done: m.done, total: m.total });
      else {
        worker.terminate();
        setProgress(null);
        if (m.type === 'error') setResult({ kind: 'error', text: m.message });
        else {
          downloadBlob(m.data, filename(true), EXT[format][1]);
          const secs = ((performance.now() - started) / 1000).toFixed(1);
          setResult({ kind: 'ok', text: `${rows.length.toLocaleString('pt-BR')} etiquetas em ${secs} s (${m.data.length >= 1e6 ? `${(m.data.length / 1e6).toFixed(1)} MB` : `${Math.ceil(m.data.length / 1024)} KB`}).`, issues: m.issues });
        }
      }
    };
    const req: ExportRequest = { format, template, rows, dpi: template.dpi, brand, copies, textAsImage };
    worker.postMessage(req);
  };

  return (
    <>
      <Section title="Exportar">
        <div className="grid2">
          <Select
            label="Formato"
            value={format}
            options={[
              ['TSPL', 'TSPL (TSC)'],
              ['ZPL', 'ZPL (Zebra)'],
              ['EPL', 'EPL2 (Eltron)'],
              ['PDF', 'PDF (1 por página)'],
              ['SVG', 'SVG (vetor)'],
            ]}
            onChange={(f) => {
              setFormat(f);
              if (f !== 'SVG' && f !== 'PDF') useDesigner.getState().setLanguage(f);
            }}
          />
          <Num label="Cópias por etiqueta" min={1} max={999} value={copies} onCommit={setCopies} />
        </div>
        {printer && (
          <Check
            label="Texto como imagem (idêntico à prévia)"
            checked={textAsImage}
            onChange={setTextAsImage}
            title="Usa a fonte da tela, não a da impressora. Payload maior."
          />
        )}
        <p className="muted">
          {template.widthMm}×{template.heightMm} mm a {template.dpi} dpi. Linha de prévia: {index + 1}.
        </p>
        <div className="row">
          <button onClick={() => void exportCurrent()}>Baixar etiqueta atual</button>
          <button className="primary" disabled={format === 'SVG' || rows.length === 0 || !!progress} onClick={exportAll}>
            Baixar lote ({rows.length.toLocaleString('pt-BR')})
          </button>
        </div>
        {progress && (
          <div className="progress" role="progressbar" aria-valuenow={progress.done} aria-valuemax={progress.total}>
            <div style={{ width: `${(100 * progress.done) / Math.max(1, progress.total)}%` }} />
          </div>
        )}
        {result && <Notice kind={result.kind === 'ok' ? 'ok' : 'error'}>{result.text}</Notice>}
        {result?.issues && result.issues.length > 0 && (
          <ul className="findings">
            {result.issues.slice(0, 10).map((i, k) => (
              <li key={k} className={i.severity}>
                {i.message}
              </li>
            ))}
          </ul>
        )}
      </Section>

      {preview && (
        <Section title={`Código ${format} (etiqueta atual)`}>
          {preview.issues.length > 0 && (
            <ul className="findings">
              {preview.issues.map((i, k) => (
                <li key={k} className={i.severity}>
                  {i.message}
                </li>
              ))}
            </ul>
          )}
          <pre className="code">{printable(preview.commands).slice(0, 20000)}</pre>
        </Section>
      )}
    </>
  );
}
