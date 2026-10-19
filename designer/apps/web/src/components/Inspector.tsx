import {
  sortedByZ,
  templateFields,
  TRANSFORMS,
  type AlignMode,
  type Barcode1DObject,
  type Barcode2DObject,
  type BoxObject,
  type ImageObject,
  type LabelObject,
  type ObjectPatch,
  type TextObject,
} from '@athos-label/engine';
import { useDesigner, useSelectedObjects } from '../store';
import { toMono } from '../lib/images';
import { Check, Num, Section, Select, Text } from './ui';

const TYPE_ICON: Record<LabelObject['type'], string> = { TEXT: 'T', BARCODE_1D: '▮▯', BARCODE_2D: '▦', IMAGE: '🖼', BOX: '▭' };

export function Inspector() {
  const selected = useSelectedObjects();
  if (selected.length === 0) return <TemplateSettings />;
  if (selected.length > 1) return <MultiSelection count={selected.length} />;
  return <ObjectProps obj={selected[0]!} />;
}

function TemplateSettings() {
  const t = useDesigner((s) => s.template);
  const { setTemplateProps } = useDesigner.getState();
  return (
    <>
      <Section title="Etiqueta">
        <Text label="Nome" value={t.name} onCommit={(name) => setTemplateProps({ name })} />
        <div className="grid2">
          <Num label="Largura" suffix="mm" min={5} max={300} value={t.widthMm} onCommit={(widthMm) => setTemplateProps({ widthMm })} />
          <Num label="Altura" suffix="mm" min={5} max={500} value={t.heightMm} onCommit={(heightMm) => setTemplateProps({ heightMm })} />
          <Select
            label="DPI da impressora"
            value={t.dpi}
            options={[
              [203, '203 (8 dots/mm)'],
              [300, '300 (12 dots/mm)'],
              [600, '600 (24 dots/mm)'],
            ]}
            onChange={(dpi) => setTemplateProps({ dpi })}
          />
        </div>
        <p className="muted">Clique num objeto para editar. Shift+clique seleciona vários.</p>
      </Section>
      <Layers />
    </>
  );
}

function MultiSelection({ count }: { count: number }) {
  const { alignSelected, distributeSelected, duplicateSelected, removeSelected } = useDesigner.getState();
  const buttons: [AlignMode, string, string][] = [
    ['left', '⇤', 'Alinhar à esquerda'],
    ['centerH', '↔', 'Centralizar na horizontal'],
    ['right', '⇥', 'Alinhar à direita'],
    ['top', '⤒', 'Alinhar ao topo'],
    ['middle', '↕', 'Centralizar na vertical'],
    ['bottom', '⤓', 'Alinhar à base'],
  ];
  return (
    <>
      <Section title={`${count} objetos selecionados`}>
        <div className="row">
          {buttons.map(([mode, icon, title]) => (
            <button key={mode} title={title} onClick={() => alignSelected(mode)}>
              {icon}
            </button>
          ))}
        </div>
        <div className="row">
          <button disabled={count < 3} onClick={() => distributeSelected('horizontal')} title="Mesmo espaço entre eles (3+ objetos)">Distribuir ↔</button>
          <button disabled={count < 3} onClick={() => distributeSelected('vertical')} title="Mesmo espaço entre eles (3+ objetos)">Distribuir ↕</button>
        </div>
        <div className="row">
          <button onClick={duplicateSelected}>Duplicar (Ctrl+D)</button>
          <button className="danger" onClick={removeSelected}>Excluir</button>
        </div>
      </Section>
      <Layers />
    </>
  );
}

function ObjectProps({ obj }: { obj: LabelObject }) {
  const { updateObject, zOrder, duplicateSelected, removeSelected } = useDesigner.getState();
  const set = (patch: ObjectPatch) => updateObject(obj.id, patch);
  const rotations: [number, string][] = [0, 90, 180, 270].map((r) => [r, `${r}°`]);
  if (!rotations.some(([r]) => r === obj.rotation)) rotations.push([obj.rotation, `${obj.rotation}° (livre)`]);

  return (
    <>
      <Section title={`${TYPE_ICON[obj.type]} ${obj.name}`}>
        <Text label="Nome" value={obj.name} onCommit={(name) => set({ name })} />
        <div className="grid2">
          <Num label="X" suffix="mm" step={0.1} value={obj.x} onCommit={(x) => set({ x })} />
          <Num label="Y" suffix="mm" step={0.1} value={obj.y} onCommit={(y) => set({ y })} />
          <Num label="Largura" suffix="mm" min={0.1} value={obj.width} onCommit={(width) => set({ width })} />
          <Num label="Altura" suffix="mm" min={0.1} value={obj.height} onCommit={(height) => set({ height })} />
          <Select label="Rotação" value={obj.rotation} options={rotations} onChange={(rotation) => set({ rotation })} />
        </div>
        <div className="row">
          <Check label="Visível" checked={obj.visible} onChange={(visible) => set({ visible })} />
          <Check label="Travado" checked={obj.locked} onChange={(locked) => set({ locked })} title="Impede editar no designer; continua sendo impresso" />
        </div>
      </Section>

      {obj.type === 'TEXT' && <TextProps obj={obj} set={set} />}
      {obj.type === 'BARCODE_1D' && <Barcode1DProps obj={obj} set={set} />}
      {obj.type === 'BARCODE_2D' && <Barcode2DProps obj={obj} set={set} />}
      {obj.type === 'IMAGE' && <ImageProps obj={obj} set={set} />}
      {obj.type === 'BOX' && <BoxProps obj={obj} set={set} />}

      <Section title="Ordem">
        <div className="row">
          <button onClick={() => zOrder(obj.id, 'front')}>Frente</button>
          <button onClick={() => zOrder(obj.id, 'forward')}>↑</button>
          <button onClick={() => zOrder(obj.id, 'backward')}>↓</button>
          <button onClick={() => zOrder(obj.id, 'back')}>Fundo</button>
        </div>
        <div className="row">
          <button onClick={duplicateSelected}>Duplicar</button>
          <button className="danger" disabled={obj.locked} onClick={removeSelected}>
            Excluir
          </button>
        </div>
      </Section>
      <Layers />
    </>
  );
}

type Setter = (patch: ObjectPatch) => void;

/** Conteúdo com ajuda: campos disponíveis nos dados e transformações. */
function ContentField({ value, onCommit }: { value: string; onCommit(v: string): void }) {
  const headers = useDesigner((s) => s.dataset?.headers ?? []);
  const t = useDesigner((s) => s.template);
  const fields = [...new Set([...headers, ...templateFields(t)])];
  return (
    <>
      <Text label="Conteúdo" multiline mono value={value} onCommit={onCommit} placeholder="Texto fixo, {{CAMPO}} ou {{PRECO * QTD | currency}}" />
      {fields.length > 0 && (
        <div className="chips" aria-label="Inserir campo">
          {fields.slice(0, 24).map((f) => (
            <button key={f} className="chip" title={`Inserir {{${f}}}`} onClick={() => onCommit(`${value}{{${/^[\p{L}_][\p{L}\p{N}_]*$/u.test(f) ? f : `[${f}]`}}}`)}>
              {f}
            </button>
          ))}
        </div>
      )}
      <details className="help">
        <summary>Transformações e funções</summary>
        <p>
          Pipes: {Object.entries(TRANSFORMS).map(([k, v]) => (
            <code key={k} title={v.help}>
              {k}
            </code>
          ))}
        </p>
        <p>
          Funções: <code>IF(QTD &gt; 10, "GRÁTIS", "PAGO")</code> <code>SEQ(1, 1, 6)</code> <code>DATEFMT(DATEADD(TODAY(), 30))</code>{' '}
          <code>UPPER LOWER PROPER TRIM LEFT RIGHT MID LEN REPLACE PAD ROUND MIN MAX COALESCE ISBLANK TEXT CURRENCY EAN13</code>. Texto: <code>&amp;</code> concatena.
        </p>
      </details>
    </>
  );
}

function TextProps({ obj, set }: { obj: TextObject; set: Setter }) {
  const font = (p: Partial<TextObject['font']>) => set({ font: { ...obj.font, ...p } });
  return (
    <Section title="Texto">
      <ContentField value={obj.content} onCommit={(content) => set({ content })} />
      <div className="grid2">
        <Text label="Fonte" value={obj.font.family} onCommit={(family) => font({ family })} />
        <Num label="Tamanho" suffix="mm" min={0.5} max={100} value={obj.font.sizeMm} onCommit={(sizeMm) => font({ sizeMm })} />
        <Select
          label="Ajuste"
          value={obj.fit}
          options={[
            ['WRAP_SHRINK', 'Quebra e reduz (auto-fit)'],
            ['SHRINK', 'Reduz em 1 linha'],
            ['WRAP', 'Só quebra linha'],
            ['NONE', 'Nenhum (corta)'],
          ]}
          onChange={(fit) => set({ fit })}
        />
        <Num label="Mínimo" suffix="mm" min={0.5} value={obj.minSizeMm} onCommit={(minSizeMm) => set({ minSizeMm })} hint="Piso do auto-fit" />
        <Select
          label="Horizontal"
          value={obj.alignment}
          options={[
            ['LEFT', 'Esquerda'],
            ['CENTER', 'Centro'],
            ['RIGHT', 'Direita'],
          ]}
          onChange={(alignment) => set({ alignment })}
        />
        <Select
          label="Vertical"
          value={obj.verticalAlignment}
          options={[
            ['TOP', 'Topo'],
            ['MIDDLE', 'Meio'],
            ['BOTTOM', 'Base'],
          ]}
          onChange={(verticalAlignment) => set({ verticalAlignment })}
        />
      </div>
      <div className="row">
        <Check label="Negrito" checked={obj.font.bold} onChange={(bold) => font({ bold })} />
        <Check label="Itálico" checked={obj.font.italic} onChange={(italic) => font({ italic })} />
        <Check
          label="Hifenizar"
          checked={obj.hyphenate === true}
          onChange={(hyphenate) => set({ hyphenate })}
          title="Quebra palavras em sílabas do português (ex.: PRO-MOÇÃO). Só vale com quebra de linha; não separa vogais nem códigos."
        />
      </div>
      <div className="grid2">
        <Select
          label="Fonte da marca"
          value={obj.brandFont ?? ''}
          options={[
            ['', 'Não (fonte acima)'],
            ['heading', 'Título do kit'],
            ['body', 'Corpo do kit'],
          ]}
          onChange={(v) => set({ brandFont: v === '' ? null : v })}
        />
        <label className="field">
          <span>Cor</span>
          <input type="color" value={obj.color} disabled={obj.useBrandColor} onChange={(e) => set({ color: e.target.value })} />
        </label>
      </div>
      <Check label="Usar cor da marca" checked={obj.useBrandColor} onChange={(useBrandColor) => set({ useBrandColor })} />
    </Section>
  );
}

function Barcode1DProps({ obj, set }: { obj: Barcode1DObject; set: Setter }) {
  return (
    <Section title="Código de barras">
      <Select
        label="Simbologia"
        value={obj.symbology}
        options={[
          ['CODE128', 'Code 128'],
          ['EAN13', 'EAN-13'],
          ['UPCA', 'UPC-A'],
          ['CODE39', 'Code 39'],
          ['EAN8', 'EAN-8'],
          ['ITF14', 'ITF-14 (caixa, GTIN-14)'],
          ['GS1_128', 'GS1-128 — (01)GTIN(17)validade(10)lote'],
        ]}
        onChange={(symbology) => set({ symbology })}
      />
      <ContentField value={obj.content} onCommit={(content) => set({ content })} />
      <div className="grid2">
        <Num label="Texto abaixo" suffix="mm" min={1} value={obj.textSizeMm} onCommit={(textSizeMm) => set({ textSizeMm })} />
        <Num label="Zona de silêncio" suffix="mm" min={0} value={obj.quietZoneMm} onCommit={(quietZoneMm) => set({ quietZoneMm })} />
      </div>
      <Check label="Mostrar dígitos" checked={obj.humanReadable} onChange={(humanReadable) => set({ humanReadable })} />
      <p className="muted">A largura do módulo é ajustada ao dot inteiro da impressora: o código pode ficar um pouco mais estreito que a caixa.</p>
    </Section>
  );
}

function Barcode2DProps({ obj, set }: { obj: Barcode2DObject; set: Setter }) {
  return (
    <Section title="Código 2D">
      <Select
        label="Tipo"
        value={obj.symbology}
        options={[
          ['QR', 'QR Code'],
          ['DATAMATRIX', 'DataMatrix'],
        ]}
        onChange={(symbology) => set({ symbology })}
      />
      <ContentField value={obj.content} onCommit={(content) => set({ content })} />
      {obj.symbology === 'QR' && (
        <Select
          label="Correção de erro"
          value={obj.ecLevel}
          options={[
            ['L', 'L (7%)'],
            ['M', 'M (15%)'],
            ['Q', 'Q (25%)'],
            ['H', 'H (30%)'],
          ]}
          onChange={(ecLevel) => set({ ecLevel })}
        />
      )}
    </Section>
  );
}

function ImageProps({ obj, set }: { obj: ImageObject; set: Setter }) {
  return (
    <Section title="Imagem">
      <Num
        label="Limiar preto/branco"
        min={1}
        max={254}
        value={obj.threshold}
        hint="Térmica não imprime cinza: abaixo do limiar vira preto"
        onCommit={async (threshold) => set({ threshold, mono: (await toMono(obj.src, threshold)).mono })}
      />
    </Section>
  );
}

function BoxProps({ obj, set }: { obj: BoxObject; set: Setter }) {
  return (
    <Section title="Caixa">
      <div className="grid2">
        <Num label="Borda" suffix="mm" min={0.1} value={obj.borderMm} onCommit={(borderMm) => set({ borderMm })} />
        <label className="field">
          <span>Cor</span>
          <input type="color" value={obj.color} disabled={obj.useBrandColor} onChange={(e) => set({ color: e.target.value })} />
        </label>
      </div>
      <div className="row">
        <Check label="Preenchida" checked={obj.filled} onChange={(filled) => set({ filled })} />
        <Check label="Cor da marca" checked={obj.useBrandColor} onChange={(useBrandColor) => set({ useBrandColor })} />
      </div>
    </Section>
  );
}

function Layers() {
  const objects = useDesigner((s) => s.template.objects);
  const selectedIds = useDesigner((s) => s.selectedIds);
  const { select, toggleSelect, updateObject } = useDesigner.getState();
  const ordered = sortedByZ(objects).reverse(); // topo primeiro, como no Figma
  return (
    <Section title={`Camadas (${objects.length})`}>
      <ul className="layers">
        {ordered.map((o) => (
          <li key={o.id} className={selectedIds.includes(o.id) ? 'active' : ''}>
            <button className="layer-name" onClick={(e) => (e.shiftKey ? toggleSelect(o.id) : select([o.id]))}>
              <span className="icon">{TYPE_ICON[o.type]}</span> {o.name}
            </button>
            <button className="icon-btn" title={o.visible ? 'Ocultar' : 'Mostrar'} onClick={() => updateObject(o.id, { visible: !o.visible })}>
              {o.visible ? '👁' : '◌'}
            </button>
            <button className="icon-btn" title={o.locked ? 'Destravar' : 'Travar'} onClick={() => updateObject(o.id, { locked: !o.locked })}>
              {o.locked ? '🔒' : '🔓'}
            </button>
          </li>
        ))}
      </ul>
    </Section>
  );
}
