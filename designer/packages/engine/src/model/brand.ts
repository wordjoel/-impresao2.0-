import type { BrandFontRole, LabelObject } from './objects';

/**
 * Kit de marca (design tokens). Hierarquia de estilo:
 *   objeto com `useBrandColor`/`brandFont` -> valor do kit
 *   caso contrário -> valor do próprio objeto (override local)
 */
export interface BrandTheme {
  name: string;
  colors: { primary: string; secondary: string; text: string };
  fonts: Record<BrandFontRole, { family: string; bold: boolean }>;
  /** Data URL do logotipo padrão. */
  logo: string | null;
  /** Nenhum objeto deve encostar na borda a menos que isso (validação). */
  safetyMarginMm: number;
}

export const DEFAULT_BRAND: BrandTheme = {
  name: 'Padrão',
  colors: { primary: '#000000', secondary: '#444444', text: '#000000' },
  fonts: {
    heading: { family: 'Helvetica', bold: true },
    body: { family: 'Helvetica', bold: false },
  },
  logo: null,
  safetyMarginMm: 1,
};

export interface ResolvedStyle {
  color: string;
  family: string | null;
  bold: boolean | null;
}

/** Aplica o kit sobre o estilo do objeto. `null` = o objeto não tem esse atributo. */
export function resolveStyle(obj: LabelObject, brand: BrandTheme): ResolvedStyle {
  if (obj.type === 'TEXT') {
    const role = obj.brandFont ? brand.fonts[obj.brandFont] : null;
    return {
      color: obj.useBrandColor ? brand.colors.text : obj.color,
      family: role ? role.family : obj.font.family,
      bold: role ? role.bold : obj.font.bold,
    };
  }
  if (obj.type === 'BOX') {
    return { color: obj.useBrandColor ? brand.colors.primary : obj.color, family: null, bold: null };
  }
  return { color: '#000000', family: null, bold: null };
}
