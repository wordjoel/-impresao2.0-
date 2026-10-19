import { useEffect, useRef, useState } from 'react';
import { useDesigner } from '../store';
import { useResolvedLabel } from '../lib/use-label';
import { simulate, type SimulatorOptions } from '../lib/simulator';
import { Notice, Num, Section } from '../components/ui';

/**
 * Simulador de impressora: a etiqueta como a cabeça térmica vai fazer, dot por dot, no DPI real,
 * com ganho de ponto, falhas e a margem que a cabeça não alcança.
 */
export function SimulatorPanel() {
  const template = useDesigner((s) => s.template);
  const label = useResolvedLabel();
  const host = useRef<HTMLDivElement>(null);
  const [opts, setOpts] = useState<Omit<SimulatorOptions, 'dpi'>>({ darkness: 10, noise: 0.5, marginMm: 0.5 });
  // 1x = largura do painel; acima disso, rola.
  const [scale, setScale] = useState(1);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const t = setTimeout(() => {
      simulate(label, { ...opts, dpi: template.dpi })
        .then((canvas) => {
          if (cancelled || !host.current) return;
          canvas.style.width = `${scale * 100}%`;
          canvas.className = 'sim-canvas';
          host.current.replaceChildren(canvas);
          setError(null);
        })
        .catch((e: Error) => setError(e.message));
    }, 150);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [label, opts, scale, template.dpi]);

  return (
    <>
      <Section title="Simulador de impressora">
        <p className="muted">
          {template.dpi} dpi: cada ponto abaixo é um dot da cabeça. Térmica não tem cinza; o que não ficar preto aqui não sai.
        </p>
        <div className="grid2">
          <Num label="Densidade (0-30)" min={0} max={30} value={opts.darkness} onCommit={(darkness) => setOpts({ ...opts, darkness })} />
          <Num label="Falhas" suffix="%" min={0} max={20} value={opts.noise} onCommit={(noise) => setOpts({ ...opts, noise })} />
          <Num label="Margem sem impressão" suffix="mm" min={0} max={5} value={opts.marginMm} onCommit={(marginMm) => setOpts({ ...opts, marginMm })} />
          <Num label="Ampliação" suffix="×" min={1} max={8} value={scale} onCommit={setScale} hint="1× = largura do painel" />
        </div>
        {error && <Notice kind="error">{error}</Notice>}
      </Section>
      <div className="sim-host" ref={host} />
    </>
  );
}
