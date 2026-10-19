import { useEffect } from 'react';
import type { ObjectPatch } from '@athos-label/engine';
import { LabelCanvas } from './components/LabelCanvas';
import { Inspector } from './components/Inspector';
import { StatusBar } from './components/StatusBar';
import { Toolbar } from './components/Toolbar';
import { BrandPanel } from './panels/BrandPanel';
import { DataPanel } from './panels/DataPanel';
import { ExportPanel } from './panels/ExportPanel';
import { PrintPanel } from './panels/PrintPanel';
import { SimulatorPanel } from './panels/SimulatorPanel';
import { TemplatesPanel } from './panels/TemplatesPanel';
import { useDesigner, type Tab } from './store';

const TABS: [Tab, string][] = [
  ['props', 'Propriedades'],
  ['data', 'Dados'],
  ['export', 'Exportar'],
  ['simulator', 'Simulador'],
  ['brand', 'Marca'],
  ['templates', 'Templates'],
  ['print', 'Imprimir'],
];

/**
 * Atalhos: Delete apaga; setas movem (passo do grid, ou 0,1 mm sem grid; Shift = 10×);
 * Ctrl+Z / Ctrl+Y desfazem/refazem; Ctrl+D duplica; Esc limpa a seleção.
 */
function useKeyboard() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (/^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || el.isContentEditable)) return;
      const s = useDesigner.getState();
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) s.redo();
        else s.undo();
        return;
      }
      if (mod && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        s.redo();
        return;
      }
      if (mod && e.key.toLowerCase() === 'd') {
        e.preventDefault();
        s.duplicateSelected();
        return;
      }
      if (e.key === 'Escape') return s.select([]);
      if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault();
        return s.removeSelected();
      }
      const delta = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
      if (!delta || s.selectedIds.length === 0) return;
      e.preventDefault();
      const step = (s.gridStep || 0.1) * (e.shiftKey ? 10 : 1);
      const round = (v: number) => Math.round(v * 1e6) / 1e6;
      const patches = new Map<string, ObjectPatch>();
      for (const o of s.template.objects) {
        if (s.selectedIds.includes(o.id) && !o.locked) patches.set(o.id, { x: round(o.x + delta[0]! * step), y: round(o.y + delta[1]! * step) });
      }
      s.updateMany(patches);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

export function App() {
  useKeyboard();
  const tab = useDesigner((s) => s.tab);
  const setTab = useDesigner((s) => s.setTab);
  const name = useDesigner((s) => s.template.name);

  return (
    <div className="app">
      <Toolbar />
      <main className="workspace">
        <section className="stage-col" aria-label={`Etiqueta ${name}`}>
          <div className="canvas-area">
            <LabelCanvas />
          </div>
          <StatusBar />
        </section>
        <aside className="panel">
          <nav className="tabs" role="tablist">
            {TABS.map(([id, label]) => (
              <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>
                {label}
              </button>
            ))}
          </nav>
          <div className="panel-body">
            {tab === 'props' && <Inspector />}
            {tab === 'data' && <DataPanel />}
            {tab === 'export' && <ExportPanel />}
            {tab === 'simulator' && <SimulatorPanel />}
            {tab === 'brand' && <BrandPanel />}
            {tab === 'templates' && <TemplatesPanel />}
            {tab === 'print' && <PrintPanel />}
          </div>
        </aside>
      </main>
    </div>
  );
}
