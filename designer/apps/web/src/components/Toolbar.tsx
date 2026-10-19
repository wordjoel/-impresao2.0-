import { useRef } from 'react';
import {
  createBarcode1D,
  createBarcode2D,
  createBox,
  createImageObject,
  createTextObject,
  type GridStep,
  type LabelObject,
} from '@athos-label/engine';
import { useDesigner } from '../store';
import { readFileAsDataUrl } from '../lib/persist';
import { toMono } from '../lib/images';

const GRID: GridStep[] = [0, 0.5, 1, 2, 5];

export function Toolbar() {
  const template = useDesigner((s) => s.template);
  const zoom = useDesigner((s) => s.zoom);
  const gridStep = useDesigner((s) => s.gridStep);
  const guidesEnabled = useDesigner((s) => s.guidesEnabled);
  const canUndo = useDesigner((s) => s.past.length > 0);
  const canRedo = useDesigner((s) => s.future.length > 0);
  const st = useDesigner.getState();
  const file = useRef<HTMLInputElement>(null);
  const add = (o: LabelObject) => st.addObject(o);

  const addImage = async (f: File) => {
    const src = await readFileAsDataUrl(f);
    const { mono, aspect } = await toMono(src);
    const width = Math.min(20, template.widthMm - 4);
    add(createImageObject({ name: f.name.replace(/\.\w+$/, ''), src, mono, width, height: Math.round((width / aspect) * 10) / 10 }));
  };

  return (
    <header className="toolbar">
      <strong className="brand" title={template.name}>
        ATHOS Label
      </strong>

      <div className="group" role="toolbar" aria-label="Adicionar objeto">
        <button onClick={() => add(createTextObject({ content: 'Texto fixo', fit: 'SHRINK' }))} title="Texto fixo">T Texto</button>
        <button onClick={() => add(createTextObject({ name: 'Campo', content: '{{PRODUTO}}' }))} title="Campo dinâmico ligado a uma coluna">{'{ }'} Campo</button>
        <button onClick={() => add(createTextObject({ name: 'Contador', content: '{{SEQ(1, 1, 6)}}', width: 20 }))} title="Auto-incremento: 000001, 000002…"># Contador</button>
        <button onClick={() => add(createTextObject({ name: 'Fórmula', content: '{{PRECO * QTD | currency}}', width: 25 }))} title="Expressão calculada">ƒ Fórmula</button>
        <button onClick={() => add(createBarcode1D())} title="Code 128, EAN-13, UPC-A, Code 39">▮▯▮ Barras</button>
        <button onClick={() => add(createBarcode2D())} title="QR Code">▦ QR</button>
        <button onClick={() => add(createBarcode2D({ name: 'DataMatrix', symbology: 'DATAMATRIX' }))} title="DataMatrix">▩ DataMatrix</button>
        <button onClick={() => file.current?.click()} title="Imagem/logo (PNG, JPG, SVG)">🖼 Imagem</button>
        <button onClick={() => add(createBox())} title="Retângulo">▭ Caixa</button>
        <button onClick={() => add(createBox({ name: 'Linha', filled: true, width: 30, height: 0.3 }))} title="Linha (caixa preenchida fina)">― Linha</button>
        <input
          ref={file}
          type="file"
          accept="image/png,image/jpeg,image/svg+xml"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) void addImage(f);
            e.target.value = '';
          }}
        />
      </div>

      <div className="group">
        <button disabled={!canUndo} onClick={st.undo} title="Desfazer (Ctrl+Z)">↶</button>
        <button disabled={!canRedo} onClick={st.redo} title="Refazer (Ctrl+Y)">↷</button>
      </div>

      <div className="group">
        <label>
          Grid
          <select value={gridStep} onChange={(e) => st.setGridStep(Number(e.target.value) as GridStep)}>
            {GRID.map((g) => (
              <option key={g} value={g}>
                {g === 0 ? 'livre' : `${String(g).replace('.', ',')} mm`}
              </option>
            ))}
          </select>
        </label>
        <label className="check">
          <input type="checkbox" checked={guidesEnabled} onChange={(e) => st.setGuidesEnabled(e.target.checked)} /> Guias
        </label>
      </div>

      <div className="group">
        <button onClick={() => st.setZoom(zoom / 1.25)} title="Diminuir zoom">−</button>
        <span className="zoom">{Math.round(zoom * 100)}%</span>
        <button onClick={() => st.setZoom(zoom * 1.25)} title="Aumentar zoom">+</button>
      </div>
    </header>
  );
}
