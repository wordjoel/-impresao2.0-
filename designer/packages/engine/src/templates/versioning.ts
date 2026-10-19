import type { LabelObject, LabelTemplate } from '../model/objects';

/**
 * Controle de versão de template: cada versão é um snapshot imutável (v1, v2, v3...). Gravar
 * sem mudança real não cria versão nova. O hash identifica o conteúdo exato na trilha de auditoria.
 */

export interface TemplateVersion {
  version: number;
  createdAt: string;
  author: string;
  note: string;
  /** SHA-256 do JSON canônico do snapshot. */
  hash: string;
  snapshot: LabelTemplate;
}

export interface TemplateHistory {
  templateId: string;
  versions: TemplateVersion[];
}

/** JSON com chaves ordenadas: o mesmo conteúdo dá sempre o mesmo texto (e o mesmo hash). */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  const o = v as Record<string, unknown>;
  return `{${Object.keys(o)
    .filter((k) => o[k] !== undefined)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${canonicalJson(o[k])}`)
    .join(',')}}`;
}

// Web Crypto: existe no navegador, em Web Worker e no Node >= 19.
declare const crypto: { subtle: { digest(alg: string, data: Uint8Array): Promise<ArrayBuffer> } };

export async function sha256Hex(data: string | Uint8Array): Promise<string> {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function latestVersion(h: TemplateHistory): TemplateVersion | null {
  return h.versions[h.versions.length - 1] ?? null;
}

export async function commitVersion(
  history: TemplateHistory,
  template: LabelTemplate,
  meta: { author: string; note: string; now?: Date },
): Promise<{ history: TemplateHistory; created: TemplateVersion | null }> {
  const snapshot = structuredClone(template);
  const hash = await sha256Hex(canonicalJson(snapshot));
  const last = latestVersion(history);
  if (last && last.hash === hash) return { history, created: null };
  const created: TemplateVersion = {
    version: (last?.version ?? 0) + 1,
    createdAt: (meta.now ?? new Date()).toISOString(),
    author: meta.author,
    note: meta.note,
    hash,
    snapshot,
  };
  return { history: { ...history, versions: [...history.versions, created] }, created };
}

export interface TemplateDiff {
  added: LabelObject[];
  removed: LabelObject[];
  changed: { before: LabelObject; after: LabelObject; fields: string[] }[];
  sizeChanged: boolean;
}

export function diffTemplates(before: LabelTemplate, after: LabelTemplate): TemplateDiff {
  const prev = new Map(before.objects.map((o) => [o.id, o]));
  const next = new Map(after.objects.map((o) => [o.id, o]));
  const changed: TemplateDiff['changed'] = [];
  for (const [id, a] of next) {
    const b = prev.get(id);
    if (!b) continue;
    const fields = Object.keys({ ...a, ...b }).filter(
      (k) => canonicalJson((a as unknown as Record<string, unknown>)[k]) !== canonicalJson((b as unknown as Record<string, unknown>)[k]),
    );
    if (fields.length) changed.push({ before: b, after: a, fields });
  }
  return {
    added: after.objects.filter((o) => !prev.has(o.id)),
    removed: before.objects.filter((o) => !next.has(o.id)),
    changed,
    sizeChanged: before.widthMm !== after.widthMm || before.heightMm !== after.heightMm,
  };
}
