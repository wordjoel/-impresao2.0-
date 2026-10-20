import { randomBytes } from 'node:crypto';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ColetorService } from './coletor';
import { createColetorLanServer, createPrintServer } from './server';
import { FileStore } from './store';

const port = Number(process.env.PORT ?? 7171);
const dataDir =
  process.env.DATA_DIR ?? path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), '.local', 'share'), 'athos-label', 'print-server');
const origins = (process.env.ALLOWED_ORIGINS ?? 'http://localhost:5173,http://127.0.0.1:5173').split(',').map((s) => s.trim());

// Listener dos coletores na rede da loja: DESLIGADO por padrão. Ex.: COLETOR_LISTEN=10.10.40.10:7172.
// COLETOR_URL é o endereço que vai no provisionamento (obrigatório se o listener escuta em 0.0.0.0).
const coletorListen = process.env.COLETOR_LISTEN?.trim() || null;
const [coletorHost, coletorPortText] = coletorListen ? coletorListen.split(':') : [];
const coletorPort = Number(coletorPortText ?? 7172);
const coletorUrl =
  process.env.COLETOR_URL?.trim() || (coletorHost && coletorHost !== '0.0.0.0' ? `http://${coletorHost}:${coletorPort}` : undefined);
if (coletorListen && !coletorUrl) {
  console.error('COLETOR_LISTEN em 0.0.0.0 precisa de COLETOR_URL (o endereço que o coletor usa para chegar a este PC)');
  process.exit(1);
}

async function loadToken(): Promise<string> {
  const file = path.join(dataDir, 'token.txt');
  try {
    return (await fs.readFile(file, 'utf8')).trim();
  } catch {
    await fs.mkdir(dataDir, { recursive: true });
    const token = randomBytes(24).toString('hex');
    await fs.writeFile(file, token, { encoding: 'utf8', mode: 0o600 });
    return token;
  }
}

const log = (m: string) => console.log(`[${new Date().toISOString()}] ${m}`);
const token = await loadToken();
const store = new FileStore(dataDir);
const coletor = new ColetorService(store, { serverUrl: coletorUrl });
const server = await createPrintServer({ store, token, allowedOrigins: origins, log, coletor });

server.listen(port, '127.0.0.1', () => {
  console.log(`ATHOS Label print-server em http://127.0.0.1:${port}`);
  console.log(`Dados: ${dataDir}`);
  console.log(`Token (cole no designer, aba Imprimir): ${path.join(dataDir, 'token.txt')}`);
});

if (coletorListen) {
  createColetorLanServer({ coletor, log }).listen(coletorPort, coletorHost, () => {
    console.log(`Coletores (rede da loja) em ${coletorUrl} (escutando em ${coletorHost}:${coletorPort})`);
  });
} else {
  console.log('Coletores: listener desligado (defina COLETOR_LISTEN para ligar)');
}
