import type { LabelObject, LabelTemplate } from './objects';

/**
 * Operações puras sobre o template. Sempre devolvem um template novo, o que deixa
 * desfazer/refazer e versionamento triviais.
 */

/** Ordem de desenho: do fundo para a frente. */
export function sortedByZ<T extends LabelObject>(objects: readonly T[]): T[] {
  return [...objects].sort((a, b) => a.zIndex - b.zIndex);
}

/** Reescreve zIndex como 0..n-1 seguindo a ordem do array. */
function reindex(ordered: LabelObject[]): LabelObject[] {
  return ordered.map((o, i) => (o.zIndex === i ? o : { ...o, zIndex: i }));
}

export function addObject(t: LabelTemplate, obj: LabelObject): LabelTemplate {
  const top = t.objects.reduce((max, o) => Math.max(max, o.zIndex), -1);
  return { ...t, objects: [...t.objects, { ...obj, zIndex: top + 1 }] };
}

export function removeObjects(t: LabelTemplate, ids: readonly string[]): LabelTemplate {
  const drop = new Set(ids);
  return { ...t, objects: reindex(sortedByZ(t.objects.filter((o) => !drop.has(o.id)))) };
}

export function removeObject(t: LabelTemplate, id: string): LabelTemplate {
  return removeObjects(t, [id]);
}

export type ObjectPatch = Partial<Omit<LabelObject, 'id' | 'type'>> & Record<string, unknown>;

export function updateObject(t: LabelTemplate, id: string, patch: ObjectPatch): LabelTemplate {
  return {
    ...t,
    objects: t.objects.map((o) => (o.id === id ? ({ ...o, ...patch } as LabelObject) : o)),
  };
}

/** Vários objetos de uma vez (alinhar, distribuir, arrastar seleção múltipla). */
export function updateObjects(t: LabelTemplate, patches: ReadonlyMap<string, ObjectPatch>): LabelTemplate {
  return {
    ...t,
    objects: t.objects.map((o) => {
      const p = patches.get(o.id);
      return p ? ({ ...o, ...p } as LabelObject) : o;
    }),
  };
}

export function duplicateObjects(
  t: LabelTemplate,
  ids: readonly string[],
  newId: () => string,
  offsetMm = 2,
): { template: LabelTemplate; newIds: string[] } {
  let next = t;
  const newIds: string[] = [];
  for (const o of sortedByZ(t.objects.filter((o) => ids.includes(o.id)))) {
    const copy = { ...o, id: newId(), x: o.x + offsetMm, y: o.y + offsetMm, name: `${o.name} (cópia)` };
    next = addObject(next, copy);
    newIds.push(copy.id);
  }
  return { template: next, newIds };
}

export type ZOrderAction = 'front' | 'back' | 'forward' | 'backward';

export function changeZOrder(t: LabelTemplate, id: string, action: ZOrderAction): LabelTemplate {
  const ordered = sortedByZ(t.objects);
  const from = ordered.findIndex((o) => o.id === id);
  if (from < 0) return t;

  const to = {
    front: ordered.length - 1,
    back: 0,
    forward: Math.min(from + 1, ordered.length - 1),
    backward: Math.max(from - 1, 0),
  }[action];

  const [moved] = ordered.splice(from, 1);
  ordered.splice(to, 0, moved!);
  return { ...t, objects: reindex(ordered) };
}
