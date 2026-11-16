// Exemplo de BACKEND do site (Node 18+, sem dependências): guarda a chave da API e repassa só o que a tela de
// revisão precisa. O navegador do revisor fala com este servidor; só este servidor fala com a API.
//
//   CONCURSOS_API_URL=http://localhost:8080 CONCURSOS_API_CHAVE=<chave> REVISOR_TOKEN=<segredo> node backend-do-site.mjs
//
// REVISOR_TOKEN existe só para o exemplo não nascer aberto: no seu site, proteja estas rotas com o login que ele já
// tem (sessão, JWT...). A chave da API NUNCA deve ir para o navegador nem para o repositório do site.
import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";

const API = (process.env.CONCURSOS_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
const CHAVE = process.env.CONCURSOS_API_CHAVE;
const TOKEN = process.env.REVISOR_TOKEN;
const PORTA = Number(process.env.PORT ?? 3001);
if (!CHAVE || !TOKEN) {
  console.error("Defina CONCURSOS_API_CHAVE e REVISOR_TOKEN.");
  process.exit(1);
}

// Só estas rotas passam: [método, rota deste servidor, rota da API].
const ROTAS = [
  ["GET", /^\/revisao\/fila$/, () => "/api/v1/revisao/fila"],
  ["GET", /^\/revisao\/cargos\/(\d+)$/, (id) => `/api/v1/revisao/cargos/${id}`],
  ["POST", /^\/revisao\/cargos\/(\d+)\/decisao$/, (id) => `/api/v1/cargos/${id}/revisao`],
  ["GET", /^\/revisao\/documentos\/(\d+)\/arquivo$/, (id) => `/api/v1/documentos/${id}/arquivo`], // o PDF
  ["GET", /^\/revisao\/alteracoes$/, () => "/api/v1/revisao/alteracoes"],
  ["POST", /^\/revisao\/alteracoes\/(\d+)\/(aplicar|vista|descartar|reabrir-cargo)$/, (id, acao) => `/api/v1/alteracoes/${id}/${acao}`],
  ["GET", /^\/revisao\/pistas$/, () => "/api/v1/revisao/pistas"],
  ["POST", /^\/revisao\/pistas\/(\d+)\/descartar$/, (id) => `/api/v1/pistas/${id}/descartar`],
];

const igual = (a, b) => a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));

async function corpo(req, limite = 1_000_000) {
  const partes = [];
  let total = 0;
  for await (const parte of req) {
    total += parte.length;
    if (total > limite) throw new Error("corpo grande demais");
    partes.push(parte);
  }
  return Buffer.concat(partes);
}

createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const enviado = req.headers["x-revisor-token"];
  if (typeof enviado !== "string" || !igual(enviado, TOKEN)) {
    res.writeHead(401, { "content-type": "application/json" }).end(JSON.stringify({ erro: "não autorizado" }));
    return;
  }
  for (const [metodo, rota, alvo] of ROTAS) {
    const m = metodo === req.method && url.pathname.match(rota);
    if (!m) continue;
    try {
      const temCorpo = req.method === "POST" && Number(req.headers["content-length"] ?? 0) > 0;
      const resposta = await fetch(API + alvo(...m.slice(1)) + url.search, {
        method: req.method,
        headers: { "X-API-Key": CHAVE, ...(temCorpo && { "content-type": "application/json" }) },
        body: temCorpo ? await corpo(req) : undefined,
      });
      // repassa status, tipo e corpo (JSON, erros problem+json ou o PDF) sem interpretar
      res.writeHead(resposta.status, { "content-type": resposta.headers.get("content-type") ?? "application/octet-stream" });
      res.end(Buffer.from(await resposta.arrayBuffer()));
    } catch (e) {
      res.writeHead(502, { "content-type": "application/json" }).end(JSON.stringify({ erro: "API indisponível" }));
    }
    return;
  }
  res.writeHead(404, { "content-type": "application/json" }).end(JSON.stringify({ erro: "rota inexistente" }));
}).listen(PORTA, "127.0.0.1", () => console.log(`Backend de exemplo em http://127.0.0.1:${PORTA}`));
