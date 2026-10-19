/// <reference lib="webworker" />
import { resolveLabel, type BrandTheme, type DataRow, type Issue, type LabelTemplate, type PrinterLanguage } from '@athos-label/engine';
import { compileLabel, labelsToPdf } from '@athos-label/exporters';
import { appMeasurer, rasterizeText } from './measure';

/**
 * Exportação em lote fora da thread da interface: 10.000 etiquetas não travam o editor.
 * Usa o template compilado (cache): objetos estáticos viram comando uma vez só.
 */
export interface ExportRequest {
  format: PrinterLanguage | 'PDF';
  template: LabelTemplate;
  rows: DataRow[];
  dpi: number;
  brand: BrandTheme;
  copies: number;
  textAsImage: boolean;
}

export type ExportMessage =
  | { type: 'progress'; done: number; total: number }
  | { type: 'done'; data: Uint8Array; issues: Issue[] }
  | { type: 'error'; message: string };

const post = (m: ExportMessage, transfer: Transferable[] = []) => (self as unknown as Worker).postMessage(m, transfer);

self.onmessage = async (e: MessageEvent<ExportRequest>) => {
  const r = e.data;
  try {
    const total = r.rows.length;
    const issues: Issue[] = [];
    const keep = (list: Issue[]) => {
      for (const i of list) if (issues.length < 200 && !issues.some((x) => x.code === i.code && x.objectId === i.objectId)) issues.push(i);
    };

    if (r.format === 'PDF') {
      const labels = r.rows.map((row, i) => {
        if (i % 200 === 0) post({ type: 'progress', done: i, total });
        const l = resolveLabel(r.template, row, { dpi: r.dpi, brand: r.brand, measurer: appMeasurer, index: i });
        keep(l.issues);
        return l;
      });
      const pdf = await labelsToPdf(labels, r.template.name);
      post({ type: 'done', data: pdf, issues }, [pdf.buffer]);
      return;
    }

    const compiled = compileLabel(r.template, {
      language: r.format,
      dpi: r.dpi,
      brand: r.brand,
      measurer: appMeasurer,
      rasterizeText: r.textAsImage ? rasterizeText : undefined,
    });
    const parts: string[] = [];
    r.rows.forEach((row, i) => {
      if (i % 500 === 0) post({ type: 'progress', done: i, total });
      const out = compiled.render(row, i, r.copies);
      keep(out.issues);
      parts.push(out.commands);
    });
    const data = compiled.encode(parts.join(''));
    post({ type: 'done', data, issues }, [data.buffer]);
  } catch (err) {
    post({ type: 'error', message: (err as Error).message });
  }
};
