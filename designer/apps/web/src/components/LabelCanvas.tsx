import { useEffect, useMemo, useReducer, useRef } from 'react';
import type Konva from 'konva';
import { Group, Layer, Line, Rect, Shape, Stage, Transformer } from 'react-konva';
import { boundsOf, pxPerMm, smartGuides, snapToGrid, sortedByZ, type BBox, type LabelObject, type ObjectPatch } from '@athos-label/engine';
import { useDesigner } from '../store';
import { drawElement, setImageLoadListener } from '../lib/draw';
import { useResolvedLabel } from '../lib/use-label';

/** Margem em px entre a borda do Stage e a etiqueta. */
const PAD = 24;
/** Distância (px na tela) para a Smart Guide "puxar" o objeto. */
const GUIDE_PX = 5;

type Native = { _context: CanvasRenderingContext2D };

/**
 * Canvas do editor. Todo o conteúdo vive num Group escalado por px/mm: as coordenadas dos nós são
 * mm do modelo. Cada objeto desenha o seu elemento RESOLVIDO (dados da linha de prévia aplicados),
 * o mesmo que vai para SVG, PDF e impressora.
 */
export function LabelCanvas() {
  const template = useDesigner((s) => s.template);
  const selectedIds = useDesigner((s) => s.selectedIds);
  const zoom = useDesigner((s) => s.zoom);
  const gridStep = useDesigner((s) => s.gridStep);
  const guidesEnabled = useDesigner((s) => s.guidesEnabled);
  const guides = useDesigner((s) => s.guides);
  const { select, toggleSelect, updateMany, setGuides } = useDesigner.getState();
  const label = useResolvedLabel();
  const k = pxPerMm(zoom);
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  const trRef = useRef<Konva.Transformer>(null);
  const nodes = useRef(new Map<string, Konva.Group>());
  const dragStart = useRef<{ positions: Map<string, { x: number; y: number }>; others: BBox[] } | null>(null);

  useEffect(() => setImageLoadListener(redraw), []);

  const elements = useMemo(() => new Map(label.elements.map((e) => [e.id, e])), [label]);
  const visible = useMemo(() => sortedByZ(template.objects.filter((o) => o.visible)), [template]);

  useEffect(() => {
    const tr = trRef.current;
    if (!tr) return;
    const selectedNodes = selectedIds
      .filter((id) => !template.objects.find((o) => o.id === id)?.locked)
      .map((id) => nodes.current.get(id))
      .filter((n): n is Konva.Group => !!n);
    tr.nodes(selectedNodes);
    tr.getLayer()?.batchDraw();
  }, [selectedIds, template]);

  const byId = (id: string) => template.objects.find((o) => o.id === id);

  const onDragStart = (obj: LabelObject) => {
    // Arrastar um objeto fora da seleção seleciona só ele.
    const ids = selectedIds.includes(obj.id) ? selectedIds : [obj.id];
    if (!selectedIds.includes(obj.id)) select([obj.id]);
    const positions = new Map<string, { x: number; y: number }>();
    for (const id of ids) {
      const o = byId(id);
      if (o && !o.locked) positions.set(id, { x: o.x, y: o.y });
    }
    const labelBox = { x: 0, y: 0, width: template.widthMm, height: template.heightMm };
    dragStart.current = { positions, others: [labelBox, ...visible.filter((o) => !positions.has(o.id)).map((o) => boundsOf(o))] };
  };

  const onDragMove = (obj: LabelObject, e: Konva.KonvaEventObject<DragEvent>) => {
    const start = dragStart.current;
    if (!start) return;
    const n = e.target;
    let x = snapToGrid(n.x(), gridStep);
    let y = snapToGrid(n.y(), gridStep);
    if (guidesEnabled) {
      const g = smartGuides(boundsOf({ ...obj, x, y }), start.others, GUIDE_PX / k);
      // A guia vence o grid no eixo em que encaixou.
      if (g.dx) x = Math.round((x + g.dx) * 1e6) / 1e6;
      if (g.dy) y = Math.round((y + g.dy) * 1e6) / 1e6;
      setGuides(g.guides);
    }
    n.position({ x, y });
    const origin = start.positions.get(obj.id)!;
    for (const [id, p] of start.positions) {
      if (id !== obj.id) nodes.current.get(id)?.position({ x: p.x + (x - origin.x), y: p.y + (y - origin.y) });
    }
  };

  const onDragEnd = () => {
    const start = dragStart.current;
    dragStart.current = null;
    setGuides([]);
    if (!start) return;
    const patches = new Map<string, ObjectPatch>();
    for (const id of start.positions.keys()) {
      const n = nodes.current.get(id);
      if (n) patches.set(id, { x: Math.round(n.x() * 1e6) / 1e6, y: Math.round(n.y() * 1e6) / 1e6 });
    }
    updateMany(patches);
  };

  const onTransformEnd = () => {
    const tr = trRef.current;
    if (!tr) return;
    const patches = new Map<string, ObjectPatch>();
    for (const n of tr.nodes()) {
      const o = byId(n.id());
      if (!o) continue;
      // O Transformer mexe em scale; o modelo guarda width/height em mm: "assa" a escala.
      const width = Math.max(1, snapToGrid(o.width * n.scaleX(), gridStep || 0.1));
      const height = Math.max(1, snapToGrid(o.height * n.scaleY(), gridStep || 0.1));
      n.scale({ x: 1, y: 1 });
      patches.set(o.id, { x: snapToGrid(n.x(), gridStep), y: snapToGrid(n.y(), gridStep), width, height, rotation: Math.round(n.rotation() * 100) / 100 });
    }
    updateMany(patches);
  };

  const gridLines = useMemo(() => {
    if (gridStep === 0 || gridStep * k < 3) return null; // grid fino demais vira borrão
    const lines: JSX.Element[] = [];
    const major = (v: number) => Math.abs(v / 10 - Math.round(v / 10)) < 1e-9;
    for (let x = gridStep; x < template.widthMm; x += gridStep) {
      lines.push(<Line key={`v${x}`} points={[x, 0, x, template.heightMm]} stroke={major(x) ? '#c7cdd6' : '#eef0f3'} strokeWidth={1} strokeScaleEnabled={false} listening={false} />);
    }
    for (let y = gridStep; y < template.heightMm; y += gridStep) {
      lines.push(<Line key={`h${y}`} points={[0, y, template.widthMm, y]} stroke={major(y) ? '#c7cdd6' : '#eef0f3'} strokeWidth={1} strokeScaleEnabled={false} listening={false} />);
    }
    return lines;
  }, [gridStep, k, template.widthMm, template.heightMm]);

  return (
    <Stage
      width={template.widthMm * k + PAD * 2}
      height={template.heightMm * k + PAD * 2}
      onMouseDown={(e) => {
        if (e.target === e.target.getStage() || e.target.name() === 'label-bg') select([]);
      }}
    >
      <Layer>
        <Group x={PAD} y={PAD} scaleX={k} scaleY={k}>
          <Rect name="label-bg" width={template.widthMm} height={template.heightMm} fill="#ffffff" shadowColor="#000" shadowOpacity={0.15} shadowBlur={8 / k} />
          {gridLines}
          {visible.map((obj) => {
            const el = elements.get(obj.id);
            return (
              <Group
                key={obj.id}
                id={obj.id}
                ref={(n) => {
                  if (n) nodes.current.set(obj.id, n);
                  else nodes.current.delete(obj.id);
                }}
                x={obj.x}
                y={obj.y}
                rotation={obj.rotation}
                draggable={!obj.locked}
                onMouseDown={(e) => {
                  e.cancelBubble = true;
                  if (e.evt.shiftKey) toggleSelect(obj.id);
                  else if (!selectedIds.includes(obj.id)) select([obj.id]);
                }}
                onTap={() => select([obj.id])}
                onDragStart={() => onDragStart(obj)}
                onDragMove={(e) => onDragMove(obj, e)}
                onDragEnd={onDragEnd}
              >
                {/* Área de clique do tamanho do objeto, mesmo onde o desenho é vazio. */}
                <Rect width={obj.width} height={obj.height} fill="rgba(0,0,0,0)" />
                {el && <Shape listening={false} sceneFunc={(ctx) => drawElement((ctx as unknown as Native)._context, el)} />}
                {selectedIds.includes(obj.id) && obj.locked && (
                  <Rect width={obj.width} height={obj.height} stroke="#98a2b3" dash={[4, 3]} strokeWidth={1} strokeScaleEnabled={false} listening={false} />
                )}
              </Group>
            );
          })}
          {guides.map((g, i) => (
            <Line
              key={i}
              points={g.orientation === 'vertical' ? [g.position, g.from, g.position, g.to] : [g.from, g.position, g.to, g.position]}
              stroke="#e31b8c"
              strokeWidth={1}
              strokeScaleEnabled={false}
              listening={false}
            />
          ))}
          <Rect width={template.widthMm} height={template.heightMm} stroke="#8a94a3" strokeWidth={1} strokeScaleEnabled={false} listening={false} />
        </Group>
        <Transformer
          ref={trRef}
          rotationSnaps={[0, 90, 180, 270]}
          keepRatio={false}
          onTransformEnd={onTransformEnd}
          boundBoxFunc={(oldBox, newBox) => (Math.abs(newBox.width) < k || Math.abs(newBox.height) < k ? oldBox : newBox)}
        />
      </Layer>
    </Stage>
  );
}
