import { useRef } from 'react';
import { createImageObject, type BrandTheme } from '@athos-label/engine';
import { useDesigner } from '../store';
import { readFileAsDataUrl } from '../lib/persist';
import { toMono } from '../lib/images';
import { Check, Num, Section, Text } from '../components/ui';

/** Kit de marca: definido uma vez, aplicado a todo objeto marcado "usar da marca". */
export function BrandPanel() {
  const brand = useDesigner((s) => s.brand);
  const { setBrand, addObject } = useDesigner.getState();
  const file = useRef<HTMLInputElement>(null);
  const set = (p: Partial<BrandTheme>) => setBrand({ ...brand, ...p });

  const insertLogo = async () => {
    if (!brand.logo) return;
    const { mono, aspect } = await toMono(brand.logo);
    addObject(createImageObject({ name: 'Logo', src: brand.logo, mono, width: 15, height: Math.round((15 / aspect) * 10) / 10 }));
  };

  return (
    <>
      <Section title="Kit de marca">
        <Text label="Nome" value={brand.name} onCommit={(name) => set({ name })} />
        <div className="grid2">
          {(['primary', 'secondary', 'text'] as const).map((k) => (
            <label key={k} className="field">
              <span>{{ primary: 'Primária', secondary: 'Secundária', text: 'Texto' }[k]}</span>
              <input type="color" value={brand.colors[k]} onChange={(e) => set({ colors: { ...brand.colors, [k]: e.target.value } })} />
            </label>
          ))}
        </div>
        <p className="muted">Impressora térmica é monocromática: as cores valem para PDF e SVG; na etiqueta tudo sai preto.</p>
      </Section>

      <Section title="Fontes">
        {(['heading', 'body'] as const).map((role) => (
          <div key={role} className="grid2">
            <Text
              label={role === 'heading' ? 'Título' : 'Corpo'}
              value={brand.fonts[role].family}
              onCommit={(family) => set({ fonts: { ...brand.fonts, [role]: { ...brand.fonts[role], family } } })}
            />
            <Check
              label="Negrito"
              checked={brand.fonts[role].bold}
              onChange={(bold) => set({ fonts: { ...brand.fonts, [role]: { ...brand.fonts[role], bold } } })}
            />
          </div>
        ))}
        <p className="muted">Objetos de texto com "Fonte da marca" usam estas. Fonte fora de Helvetica/Arial: marque "texto como imagem" ao exportar para sair igual na impressora.</p>
      </Section>

      <Section title="Logotipo e margem">
        {brand.logo && <img className="logo-preview" src={brand.logo} alt="Logotipo da marca" />}
        <div className="row">
          <button onClick={() => file.current?.click()}>{brand.logo ? 'Trocar logo' : 'Enviar logo'}</button>
          <button disabled={!brand.logo} onClick={() => void insertLogo()}>
            Inserir na etiqueta
          </button>
          {brand.logo && (
            <button className="link" onClick={() => set({ logo: null })}>
              remover
            </button>
          )}
          <input
            ref={file}
            type="file"
            accept="image/png,image/jpeg,image/svg+xml"
            hidden
            onChange={async (e) => {
              const f = e.target.files?.[0];
              if (f) set({ logo: await readFileAsDataUrl(f) });
              e.target.value = '';
            }}
          />
        </div>
        <Num
          label="Margem de segurança"
          suffix="mm"
          min={0}
          max={10}
          value={brand.safetyMarginMm}
          onCommit={(safetyMarginMm) => set({ safetyMarginMm })}
          hint="Objeto dentro dela gera alerta (o corte da etiqueta varia)"
        />
      </Section>
    </>
  );
}
