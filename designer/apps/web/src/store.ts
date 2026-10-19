import { create } from 'zustand';
import {
  addObject as addObj,
  align,
  applyMapping,
  changeZOrder,
  commitVersion,
  createBarcode1D,
  createBarcode2D,
  createId,
  createTemplate,
  createTextObject,
  DEFAULT_BRAND,
  deriveVariant,
  distribute,
  duplicateObjects,
  generateMockRows,
  removeObjects,
  resolveVariant,
  sanitizeRows,
  updateObject as updObj,
  updateObjects,
  type AlignMode,
  type BrandTheme,
  type DataRow,
  type DataSet,
  type GridStep,
  type Guide,
  type LabelObject,
  type LabelTemplate,
  type ObjectPatch,
  type PrinterLanguage,
  type TemplateHistory,
  type TemplateVariant,
  type TemplateVersion,
  type ZOrderAction,
} from '@athos-label/engine';
import { load, save } from './lib/persist';

export type Tab = 'props' | 'data' | 'export' | 'simulator' | 'brand' | 'templates' | 'print';

export interface LibraryEntry {
  id: string;
  name: string;
  /** Template raiz (sem base). */
  template: LabelTemplate | null;
  /** Variante: só a diferença para a base (herança). */
  variant: TemplateVariant | null;
  history: TemplateHistory;
}

interface DesignerState {
  template: LabelTemplate;
  past: LabelTemplate[];
  future: LabelTemplate[];
  selectedIds: string[];
  zoom: number;
  gridStep: GridStep;
  guidesEnabled: boolean;
  guides: Guide[];
  brand: BrandTheme;
  dataset: DataSet | null;
  mapping: Record<string, string>;
  sanitize: boolean;
  previewIndex: number;
  tab: Tab;
  language: PrinterLanguage;
  library: Record<string, LibraryEntry>;

  commit(next: LabelTemplate): void;
  undo(): void;
  redo(): void;
  select(ids: string[]): void;
  toggleSelect(id: string): void;
  addObject(obj: LabelObject): void;
  updateObject(id: string, patch: ObjectPatch): void;
  updateMany(patches: Map<string, ObjectPatch>): void;
  removeSelected(): void;
  duplicateSelected(): void;
  zOrder(id: string, action: ZOrderAction): void;
  alignSelected(mode: AlignMode): void;
  distributeSelected(axis: 'horizontal' | 'vertical'): void;
  setTemplateProps(patch: Partial<Pick<LabelTemplate, 'widthMm' | 'heightMm' | 'dpi' | 'name'>>): void;
  setZoom(z: number): void;
  setGridStep(s: GridStep): void;
  setGuidesEnabled(v: boolean): void;
  setGuides(g: Guide[]): void;
  setBrand(b: BrandTheme): void;
  setDataset(ds: DataSet | null): void;
  setMapping(m: Record<string, string>): void;
  setSanitize(v: boolean): void;
  setPreviewIndex(i: number): void;
  setTab(t: Tab): void;
  setLanguage(l: PrinterLanguage): void;
  newTemplate(): void;
  openTemplate(id: string): void;
  createVariant(name: string): void;
  deleteTemplate(id: string): void;
  saveVersion(note: string, author: string): Promise<TemplateVersion | null>;
  restoreVersion(v: TemplateVersion): void;
  resolveEntry(id: string): LabelTemplate | null;
}

/** Etiqueta de preço 50x30 de exemplo: o app abre útil, já com dados fictícios. */
function demoTemplate(): LabelTemplate {
  let t: LabelTemplate = { ...createTemplate(50, 30, 'Etiqueta de Preço 50x30'), id: createId() };
  const font = (sizeMm: number, bold: boolean) => ({ family: 'Helvetica', sizeMm, bold, italic: false });
  t = addObj(t, createTextObject({ name: 'Produto', content: '{{PRODUTO}}', x: 2, y: 1.5, width: 34, height: 7, font: font(3.2, true), verticalAlignment: 'TOP' }));
  t = addObj(t, createTextObject({ name: 'Preço', content: '{{PRECO | currency}}', x: 2, y: 9, width: 34, height: 6, font: font(5, true), fit: 'SHRINK' }));
  t = addObj(t, createBarcode2D({ name: 'QR', content: '{{SKU}}', x: 37.5, y: 1.5, width: 11, height: 11 }));
  t = addObj(t, createBarcode1D({ name: 'EAN-13', symbology: 'EAN13', content: '{{EAN}}', x: 4, y: 16, width: 42, height: 10, textSizeMm: 2.2 }));
  t = addObj(t, createTextObject({ name: 'Validade', content: 'Val. {{VALIDADE | date}}  Lote {{LOTE}}', x: 2, y: 26, width: 46, height: 3, font: font(2, false), alignment: 'CENTER' }));
  return t;
}

function rootEntry(t: LabelTemplate, history?: TemplateHistory): LibraryEntry {
  return { id: t.id, name: t.name, template: t, variant: null, history: history ?? { templateId: t.id, versions: [] } };
}

const saved = {
  library: load<Record<string, LibraryEntry>>('library', {}),
  currentId: load<string | null>('currentId', null),
  brand: load<BrandTheme>('brand', DEFAULT_BRAND),
  settings: load('settings', { zoom: 2, gridStep: 1 as GridStep, guidesEnabled: true, language: 'TSPL' as PrinterLanguage, sanitize: true }),
};

function resolveFrom(library: Record<string, LibraryEntry>, id: string, depth = 0): LabelTemplate | null {
  const e = library[id];
  if (!e || depth > 10) return null;
  if (e.template) return e.template;
  if (!e.variant) return null;
  const base = resolveFrom(library, e.variant.baseTemplateId, depth + 1);
  return base ? resolveVariant(base, e.variant) : null;
}

/** Atualiza a entrada do template na biblioteca (variante guarda só a diferença para a base). */
function withEntry(library: Record<string, LibraryEntry>, t: LabelTemplate, history?: TemplateHistory): Record<string, LibraryEntry> {
  const h = history ?? library[t.id]?.history ?? { templateId: t.id, versions: [] };
  const base = t.baseTemplateId ? resolveFrom(library, t.baseTemplateId) : null;
  const entry: LibraryEntry = base
    ? { id: t.id, name: t.name, template: null, variant: deriveVariant(base, t), history: h }
    : rootEntry(t, h);
  return { ...library, [t.id]: entry };
}

const initialLibrary = Object.keys(saved.library).length
  ? saved.library
  : (() => {
      const t = demoTemplate();
      return { [t.id]: rootEntry(t) };
    })();
const initialTemplate =
  (saved.currentId && resolveFrom(initialLibrary, saved.currentId)) || resolveFrom(initialLibrary, Object.keys(initialLibrary)[0]!)!;

export const useDesigner = create<DesignerState>((set, get) => ({
  template: initialTemplate,
  past: [],
  future: [],
  selectedIds: [],
  zoom: saved.settings.zoom,
  gridStep: saved.settings.gridStep,
  guidesEnabled: saved.settings.guidesEnabled,
  guides: [],
  brand: saved.brand,
  dataset: generateMockRows(50, 42),
  mapping: {},
  sanitize: saved.settings.sanitize,
  previewIndex: 0,
  tab: 'props',
  language: saved.settings.language,
  library: initialLibrary,

  commit: (next) =>
    set((s) => ({ template: next, past: [...s.past.slice(-99), s.template], future: [], library: withEntry(s.library, next) })),

  undo: () =>
    set((s) => {
      const prev = s.past[s.past.length - 1];
      if (!prev) return s;
      return { template: prev, past: s.past.slice(0, -1), future: [s.template, ...s.future], library: withEntry(s.library, prev), selectedIds: [] };
    }),

  redo: () =>
    set((s) => {
      const next = s.future[0];
      if (!next) return s;
      return { template: next, past: [...s.past, s.template], future: s.future.slice(1), library: withEntry(s.library, next), selectedIds: [] };
    }),

  select: (ids) => set({ selectedIds: ids }),
  toggleSelect: (id) =>
    set((s) => ({ selectedIds: s.selectedIds.includes(id) ? s.selectedIds.filter((x) => x !== id) : [...s.selectedIds, id] })),

  addObject: (obj) => {
    get().commit(addObj(get().template, obj));
    set({ selectedIds: [obj.id], tab: 'props' });
  },
  updateObject: (id, patch) => get().commit(updObj(get().template, id, patch)),
  updateMany: (patches) => {
    if (patches.size) get().commit(updateObjects(get().template, patches));
  },
  removeSelected: () => {
    const { selectedIds, template } = get();
    const removable = selectedIds.filter((id) => !template.objects.find((o) => o.id === id)?.locked);
    if (removable.length === 0) return;
    get().commit(removeObjects(template, removable));
    set({ selectedIds: [] });
  },
  duplicateSelected: () => {
    const { template, selectedIds } = get();
    if (!selectedIds.length) return;
    const r = duplicateObjects(template, selectedIds, createId);
    get().commit(r.template);
    set({ selectedIds: r.newIds });
  },
  zOrder: (id, action) => get().commit(changeZOrder(get().template, id, action)),

  alignSelected: (mode) => {
    const { template, selectedIds } = get();
    const items = template.objects.filter((o) => selectedIds.includes(o.id) && !o.locked);
    if (items.length) get().updateMany(align(items, mode, { x: 0, y: 0, width: template.widthMm, height: template.heightMm }));
  },
  distributeSelected: (axis) => {
    const { template, selectedIds } = get();
    get().updateMany(distribute(template.objects.filter((o) => selectedIds.includes(o.id) && !o.locked), axis));
  },

  setTemplateProps: (patch) => get().commit({ ...get().template, ...patch }),
  setZoom: (zoom) => set({ zoom: Math.min(Math.max(zoom, 0.5), 10) }),
  setGridStep: (gridStep) => set({ gridStep }),
  setGuidesEnabled: (guidesEnabled) => set({ guidesEnabled }),
  setGuides: (guides) => set({ guides }),
  setBrand: (brand) => set({ brand }),
  setDataset: (dataset) => set({ dataset, previewIndex: 0 }),
  setMapping: (mapping) => set({ mapping }),
  setSanitize: (sanitize) => set({ sanitize }),
  setPreviewIndex: (i) => set((s) => ({ previewIndex: Math.max(0, Math.min(i, (s.dataset?.rows.length ?? 1) - 1)) })),
  setTab: (tab) => set({ tab }),
  setLanguage: (language) => set({ language }),

  newTemplate: () => {
    const t = { ...createTemplate(50, 30, 'Nova etiqueta'), id: createId() };
    set((s) => ({ template: t, past: [], future: [], selectedIds: [], library: { ...s.library, [t.id]: rootEntry(t) } }));
  },
  openTemplate: (id) => {
    const t = resolveFrom(get().library, id);
    if (t) set({ template: t, past: [], future: [], selectedIds: [] });
  },
  createVariant: (name) => {
    const base = get().template;
    const t: LabelTemplate = { ...structuredClone(base), id: createId(), name, baseTemplateId: base.id };
    set((s) => ({ template: t, past: [], future: [], selectedIds: [], library: withEntry(s.library, t, { templateId: t.id, versions: [] }) }));
  },
  deleteTemplate: (id) => {
    const { library, template } = get();
    // Base com variantes não sai: as variantes perderiam a herança.
    if (Object.values(library).some((e) => e.variant?.baseTemplateId === id)) return;
    const rest = { ...library };
    delete rest[id];
    if (Object.keys(rest).length === 0) return;
    set({ library: rest });
    if (template.id === id) get().openTemplate(Object.keys(rest)[0]!);
  },
  saveVersion: async (note, author) => {
    const { template, library } = get();
    const history = library[template.id]?.history ?? { templateId: template.id, versions: [] };
    const r = await commitVersion(history, template, { author, note });
    set((s) => ({ library: withEntry(s.library, template, r.history) }));
    return r.created;
  },
  restoreVersion: (v) => get().commit({ ...structuredClone(v.snapshot), id: get().template.id }),
  resolveEntry: (id) => resolveFrom(get().library, id),
}));

// Autosave com atraso: arrastar objeto não grava dezenas de vezes por segundo.
let timer: ReturnType<typeof setTimeout> | undefined;
useDesigner.subscribe((s) => {
  clearTimeout(timer);
  timer = setTimeout(() => {
    save('library', s.library);
    save('currentId', s.template.id);
    save('brand', s.brand);
    save('settings', { zoom: s.zoom, gridStep: s.gridStep, guidesEnabled: s.guidesEnabled, language: s.language, sanitize: s.sanitize });
  }, 400);
});

export function useSelectedObjects(): LabelObject[] {
  const template = useDesigner((s) => s.template);
  const ids = useDesigner((s) => s.selectedIds);
  return template.objects.filter((o) => ids.includes(o.id));
}

/** Linhas efetivas: fonte -> saneamento -> mapeamento de colunas. Memorizado por referência. */
let rowsCache: { ds: DataSet | null; mapping: Record<string, string>; sanitize: boolean; rows: DataRow[] } | null = null;
export function effectiveRows(s: Pick<DesignerState, 'dataset' | 'mapping' | 'sanitize'>): DataRow[] {
  if (rowsCache && rowsCache.ds === s.dataset && rowsCache.mapping === s.mapping && rowsCache.sanitize === s.sanitize) return rowsCache.rows;
  const base = s.dataset?.rows ?? [];
  const clean = s.sanitize ? sanitizeRows(base).rows : base;
  const rows = Object.keys(s.mapping).length ? clean.map((r) => applyMapping(r, s.mapping)) : clean;
  rowsCache = { ds: s.dataset, mapping: s.mapping, sanitize: s.sanitize, rows };
  return rows;
}

export function useRows(): DataRow[] {
  const dataset = useDesigner((s) => s.dataset);
  const mapping = useDesigner((s) => s.mapping);
  const sanitize = useDesigner((s) => s.sanitize);
  return effectiveRows({ dataset, mapping, sanitize });
}

export function usePreviewRow(): DataRow {
  const rows = useRows();
  const i = useDesigner((s) => s.previewIndex);
  return rows[i] ?? {};
}
