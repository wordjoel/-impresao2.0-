import type { LabelObject, LabelTemplate } from '../model/objects';
import { canonicalJson } from './versioning';

/**
 * Herança de template: a variante ("Etiqueta de Promoção") guarda só o que difere da base
 * ("Etiqueta de Preço 50x30"). Mudou a base, a variante herda a mudança — exceto nos campos que
 * ela mesma sobrescreveu.
 */
export interface TemplateVariant {
  id: string;
  name: string;
  baseTemplateId: string;
  /** Campos alterados por objeto da base. */
  overrides: Record<string, Record<string, unknown>>;
  /** Objetos que só existem na variante. */
  added: LabelObject[];
  /** Objetos da base escondidos na variante. */
  removed: string[];
  size: { widthMm: number; heightMm: number } | null;
}

export function resolveVariant(base: LabelTemplate, v: TemplateVariant): LabelTemplate {
  const removed = new Set(v.removed);
  const objects = base.objects
    .filter((o) => !removed.has(o.id))
    .map((o) => (v.overrides[o.id] ? ({ ...o, ...v.overrides[o.id] } as LabelObject) : o));
  return {
    ...base,
    id: v.id,
    name: v.name,
    widthMm: v.size?.widthMm ?? base.widthMm,
    heightMm: v.size?.heightMm ?? base.heightMm,
    baseTemplateId: base.id,
    objects: [...objects, ...v.added],
  };
}

/** Do template editado (completo) para a forma compacta da variante: só as diferenças. */
export function deriveVariant(base: LabelTemplate, edited: LabelTemplate): TemplateVariant {
  const baseById = new Map(base.objects.map((o) => [o.id, o]));
  const overrides: TemplateVariant['overrides'] = {};
  const added: LabelObject[] = [];
  for (const o of edited.objects) {
    const b = baseById.get(o.id);
    if (!b) {
      added.push(o);
      continue;
    }
    const diff: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(o)) {
      if (canonicalJson(val) !== canonicalJson((b as unknown as Record<string, unknown>)[k])) diff[k] = val;
    }
    if (Object.keys(diff).length) overrides[o.id] = diff;
  }
  const editedIds = new Set(edited.objects.map((o) => o.id));
  const sameSize = edited.widthMm === base.widthMm && edited.heightMm === base.heightMm;
  return {
    id: edited.id,
    name: edited.name,
    baseTemplateId: base.id,
    overrides,
    added,
    removed: base.objects.filter((o) => !editedIds.has(o.id)).map((o) => o.id),
    size: sameSize ? null : { widthMm: edited.widthMm, heightMm: edited.heightMm },
  };
}
