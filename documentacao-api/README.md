# Documentação da API — para quem faz o site

Esta pasta é para a equipe do **site**: o que a API de concursos para Técnicos Industriais oferece e como consumi-la.
A API em si (código, testes, robô de coleta) fica em [`../concursos-tecnicos`](../concursos-tecnicos).

| Arquivo | O que é |
|---|---|
| [API.md](API.md) | referência: chave, CORS, convenções, a leitura pública, as rotas de revisão, erros e exemplos de consumo |
| [exemplos/vagas.html](exemplos/vagas.html) | página que lista as vagas direto do navegador (sem chave), com filtros |
| [exemplos/backend-do-site.mjs](exemplos/backend-do-site.mjs) | backend de exemplo (Node 18+, sem dependências) que guarda a chave e repassa a tela de revisão |

## Em duas linhas

- **`GET /api/v1/publico/vagas`** é aberto e traz só o que uma pessoa aprovou. Pode ser chamado do navegador, se a origem do
  site estiver em `CONCURSOS_ORIGENS_PERMITIDAS` no servidor da API.
- **Todo o resto** exige o cabeçalho `X-API-Key` e deve ser chamado **só pelo backend do site**. A chave nunca vai para o navegador.

## Ligando a API para testar

A chave é gerada na 1ª execução da API, em `dados/chave-api.txt` (dentro de `concursos-tecnicos`). Os comandos de
`API.md` que citam `dados/chave-api.txt` ou `rodar-api` são para rodar na pasta `concursos-tecnicos`.

```powershell
cd ..\concursos-tecnicos
.\rodar-api.ps1        # http://localhost:8080  (Swagger: /swagger-ui.html)
```
