import {
  PDFDocument,
  StandardFonts,
  concatTransformationMatrix,
  popGraphicsState,
  pushGraphicsState,
  rgb,
  type PDFFont,
  type PDFImage,
  type PDFPage,
} from 'pdf-lib';
import type { ResolvedElement, ResolvedLabel } from '@athos-label/engine';
import { layoutLines, runs } from './common';

/**
 * PDF vetorial, uma etiqueta por página no tamanho físico. Fontes Helvetica padrão do PDF:
 * mesmas métricas AFM do medidor do engine, então a quebra de linha do PDF bate com a prévia.
 */

const PT_PER_MM = 72 / 25.4;

function color(hex: string) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex);
  const v = m ? parseInt(m[1]!, 16) : 0;
  return rgb(((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255);
}

/** Helvetica padrão usa WinAnsi; caractere fora dele vira "?" em vez de derrubar a exportação. */
function encodable(font: PDFFont, text: string): string {
  let out = '';
  for (const ch of text) {
    try {
      font.encodeText(ch);
      out += ch;
    } catch {
      out += '?';
    }
  }
  return out;
}

export async function labelsToPdf(labels: readonly ResolvedLabel[], title = 'Etiquetas'): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  doc.setTitle(title);
  doc.setProducer('ATHOS Label');
  const fonts = {
    regular: await doc.embedFont(StandardFonts.Helvetica),
    bold: await doc.embedFont(StandardFonts.HelveticaBold),
    italic: await doc.embedFont(StandardFonts.HelveticaOblique),
    boldItalic: await doc.embedFont(StandardFonts.HelveticaBoldOblique),
  };
  const images = new Map<string, PDFImage | null>();

  for (const label of labels) {
    const page = doc.addPage([label.widthMm * PT_PER_MM, label.heightMm * PT_PER_MM]);
    // Daqui em diante, unidades em mm com Y para baixo (igual à tela e ao modelo).
    page.pushOperators(pushGraphicsState(), concatTransformationMatrix(PT_PER_MM, 0, 0, -PT_PER_MM, 0, label.heightMm * PT_PER_MM));
    for (const el of label.elements) {
      if (el.kind === 'image' && !images.has(el.src)) images.set(el.src, await embedImage(doc, el.src));
      drawElement(page, el, fonts, images);
    }
    page.pushOperators(popGraphicsState());
  }
  return doc.save();
}

async function embedImage(doc: PDFDocument, src: string): Promise<PDFImage | null> {
  const m = /^data:image\/(png|jpe?g);base64,(.*)$/i.exec(src);
  if (!m) return null;
  const bin = atob(m[2]!);
  const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  return m[1]!.toLowerCase() === 'png' ? doc.embedPng(bytes) : doc.embedJpg(bytes);
}

function drawElement(
  page: PDFPage,
  el: ResolvedElement,
  fonts: Record<'regular' | 'bold' | 'italic' | 'boldItalic', PDFFont>,
  images: Map<string, PDFImage | null>,
) {
  if (el.kind === 'error') return;
  const r = (el.rotation * Math.PI) / 180;
  page.pushOperators(pushGraphicsState(), concatTransformationMatrix(Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), el.x, el.y));
  const black = rgb(0, 0, 0);
  // Texto e imagem são desenhados "de pé": desfaz o Y invertido só para eles.
  const upright = (x: number, y: number, draw: () => void) => {
    page.pushOperators(pushGraphicsState(), concatTransformationMatrix(1, 0, 0, -1, x, y));
    draw();
    page.pushOperators(popGraphicsState());
  };

  switch (el.kind) {
    case 'text': {
      const font = el.bold ? (el.italic ? fonts.boldItalic : fonts.bold) : el.italic ? fonts.italic : fonts.regular;
      for (const l of layoutLines(el)) {
        const text = encodable(font, l.text);
        const w = font.widthOfTextAtSize(text, el.fontSizeMm);
        const x = l.anchor === 'start' ? 0 : l.anchor === 'middle' ? l.anchorX - w / 2 : l.anchorX - w;
        upright(x, l.baseline, () => page.drawText(text, { x: 0, y: 0, size: el.fontSizeMm, font, color: color(el.color) }));
      }
      break;
    }
    case 'barcode1d':
      for (const [s, len] of runs(el.modules)) {
        page.drawRectangle({ x: s * el.moduleMm, y: 0, width: len * el.moduleMm, height: el.barHeightMm, color: black });
      }
      if (el.humanReadable) {
        const w = fonts.regular.widthOfTextAtSize(el.humanText, el.textSizeMm);
        upright(el.barsWidthMm / 2 - w / 2, el.barHeightMm + el.textSizeMm, () =>
          page.drawText(el.humanText, { x: 0, y: 0, size: el.textSizeMm, font: fonts.regular, color: black }),
        );
      }
      break;
    case 'barcode2d':
      for (let row = 0; row < el.rows; row++) {
        for (const [s, len] of runs(el.matrix, row * el.cols, el.cols)) {
          page.drawRectangle({ x: s * el.moduleMm, y: row * el.moduleMm, width: len * el.moduleMm, height: el.moduleMm, color: black });
        }
      }
      break;
    case 'image': {
      const img = images.get(el.src);
      if (img) upright(0, el.height, () => page.drawImage(img, { x: 0, y: 0, width: el.width, height: el.height }));
      break;
    }
    case 'box':
      if (el.filled) page.drawRectangle({ x: 0, y: 0, width: el.width, height: el.height, color: color(el.color) });
      else {
        const b = el.borderMm;
        page.drawRectangle({
          x: b / 2,
          y: b / 2,
          width: Math.max(0, el.width - b),
          height: Math.max(0, el.height - b),
          borderColor: color(el.color),
          borderWidth: b,
        });
      }
      break;
  }
  page.pushOperators(popGraphicsState());
}
