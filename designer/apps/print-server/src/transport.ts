import net from 'node:net';
import type { PrinterProfile, PrinterStatus } from '@athos-label/engine';

/**
 * Socket TCP "RAW" (porta 9100), o mesmo transporte validado no Print Bridge.
 * `send` só resolve quando todos os bytes foram aceitos e a conexão fechou sem erro: isso é
 * SENT, não "impresso".
 */
export function sendTcp(host: string, port: number, payload: Uint8Array, timeoutMs = 10_000): Promise<void> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let settled = false;
    const done = (err?: Error) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (err) reject(err);
      else resolve();
    };
    socket.setTimeout(timeoutMs, () => done(new Error(`tempo esgotado (${timeoutMs} ms) em ${host}:${port}`)));
    socket.on('error', (e: NodeJS.ErrnoException) => done(new Error(describe(e, host, port))));
    socket.on('connect', () => {
      socket.end(Buffer.from(payload.buffer, payload.byteOffset, payload.byteLength), () => done());
    });
  });
}

function describe(e: NodeJS.ErrnoException, host: string, port: number): string {
  if (e.code === 'ECONNREFUSED') return `conexão recusada em ${host}:${port}`;
  if (e.code === 'EHOSTUNREACH' || e.code === 'ENETUNREACH') return `${host} inalcançável`;
  if (e.code === 'ETIMEDOUT') return `${host} não respondeu`;
  return e.message;
}

/** Envia um comando de status e coleta a resposta por até `timeoutMs`. */
function query(host: string, port: number, request: Uint8Array, minBytes: number, timeoutMs: number): Promise<Buffer | null> {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    const chunks: Buffer[] = [];
    let connected = false;
    const finish = () => {
      socket.destroy();
      resolve(chunks.length ? Buffer.concat(chunks) : null);
    };
    const timer = setTimeout(() => (connected ? finish() : (socket.destroy(), reject(new Error('offline')))), timeoutMs);
    socket.on('error', () => {
      clearTimeout(timer);
      reject(new Error('offline'));
    });
    socket.on('connect', () => {
      connected = true;
      socket.write(request);
    });
    socket.on('data', (d) => {
      chunks.push(d);
      if (Buffer.concat(chunks).length >= minBytes) {
        clearTimeout(timer);
        finish();
      }
    });
  });
}

/**
 * ZPL ~HS: 3 linhas entre STX/ETX.
 *   linha 1: aaa,b,c,...  b = sem papel, c = pausada
 *   linha 2: mmm,n,o,...  o = cabeça aberta
 */
export function parseZplHostStatus(raw: string): PrinterStatus {
  const lines = raw.split('\x03').map((l) => l.replace(/[\x02\r\n]/g, '').split(','));
  const l1 = lines[0];
  const l2 = lines[1];
  if (!l1 || l1.length < 3 || !l2 || l2.length < 3) return 'UNKNOWN';
  if (l2[2] === '1') return 'HEAD_OPEN';
  if (l1[1] === '1') return 'PAPER_OUT';
  if (l1[2] === '1') return 'PAUSED';
  return 'READY';
}

/** TSPL <ESC>!? devolve 1 byte: bit0 cabeça aberta, bit2 sem papel, bit4 pausa, bit6 tampa aberta. */
export function parseTsplStatus(byte: number): PrinterStatus {
  if (byte & 0b0100_0001) return 'HEAD_OPEN';
  if (byte & 0b0000_0110) return 'PAPER_OUT';
  if (byte & 0b0001_0000) return 'PAUSED';
  return 'READY';
}

export async function printerStatus(p: PrinterProfile, timeoutMs = 2_000): Promise<PrinterStatus> {
  try {
    if (p.language === 'ZPL') {
      const r = await query(p.host, p.port, new TextEncoder().encode('~HS'), 60, timeoutMs);
      return r ? parseZplHostStatus(r.toString('latin1')) : 'UNKNOWN';
    }
    if (p.language === 'TSPL') {
      const r = await query(p.host, p.port, Uint8Array.of(0x1b, 0x21, 0x3f), 1, timeoutMs);
      return r ? parseTsplStatus(r[0]!) : 'UNKNOWN';
    }
    // EPL não tem consulta de status confiável pela rede: só confere se a porta atende.
    await query(p.host, p.port, new Uint8Array(0), 1, Math.min(timeoutMs, 500));
    return 'UNKNOWN';
  } catch {
    return 'OFFLINE';
  }
}
