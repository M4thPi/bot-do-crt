# concursos-tecnicos

Busca, análise e validação de concursos públicos para **Técnicos Industriais**. É uma **API**: a interface com o
usuário é o site, que consome o que está aqui.

> Princípio: nenhuma vaga é apresentada como válida para o técnico sem que os requisitos sejam
> confirmados no edital oficial. Todo dado tem evidência (documento, página, item, trecho literal)
> ou aparece como **"Não identificado no edital consultado."**

**API para o site (chave, endpoints, erros e exemplos de consumo): [documentacao-api/API.md](documentacao-api/API.md).**

**Passo a passo do robô, como rodar e como acrescentar sites: [docs/COMO-FUNCIONA.md](docs/COMO-FUNCIONA.md).**

**Mac e servidor (Docker, HTTPS, PostgreSQL): [docs/IMPLANTACAO.md](docs/IMPLANTACAO.md).**

## Como funciona

```
PDF ─► Leitor (texto por página) ─► Estrutura (itens 4.2.3, anexos) ─► Detector de cargos
    ─► Requisito de formação + conselho + valores ─► Verificador de evidências ─► Regras ─► API/relatório
```

- **Código extrai, verificador confere, regras classificam.** O trecho citado precisa existir na página
  informada e o valor precisa sair do próprio trecho, senão o campo fica vazio.
- **O título do cargo não conta.** "Técnico em Edificações" com requisito "Ensino médio completo" é
  NÃO COMPATÍVEL. A formação só é lida no texto do requisito.
- **Conselho é literal.** "conselho competente" nunca vira CRT-RJ. `exigeCrtRj = SIM` só com menção
  explícita ao CRT-RJ.
- **Fonte não oficial nunca gera COMPATÍVEL.** Cópia de agregador ou envio sem URL fica no máximo POSSIVELMENTE,
  e não substitui uma análise feita sobre documento oficial.
- **Salário-base ≠ remuneração.** Valor sem rótulo (linha de tabela) fica "a confirmar".

| Classificação | Quando |
|---|---|
| COMPATÍVEL | formação técnica industrial do catálogo no requisito, ou registro em CFT/CRT exigido no cargo |
| POSSIVELMENTE COMPATÍVEL | "curso técnico em qualquer área", formação ainda não validada no catálogo, ou fonte não oficial |
| NÃO COMPATÍVEL | formação de outra área (Enfermagem…) ou só escolaridade (ensino médio, superior) |
| NÃO FOI POSSÍVEL CONFIRMAR | requisito não localizado ou documento sem texto (escaneado) |

O catálogo de formações está em `src/main/resources/catalogo/formacoes.json`
(`industrial: true | false | null`). **Revisar com o CRT-RJ antes de produção**: itens `null` ainda
não foram validados.

## Rodar

Requer Java 25. Não precisa de Maven instalado (usa o wrapper).

```powershell
.\mvnw.cmd package                     # compila + testes
.\analisar.ps1 C:\editais\edital.pdf   # relatório no terminal (markdown)
.\analisar.ps1 edital.pdf --oficial --saida relatorio.md
.\analisar.ps1 https://site-oficial/edital.pdf --json
.\analisar.ps1 edital.pdf --texto      # texto extraído, página a página (diagnóstico)
.\rodar-api.ps1                        # API em http://localhost:8080 (documentação interativa: /swagger-ui.html)
```

No Mac/Linux: `./mvnw package`, `./rodar-api.sh` e `./analisar.sh edital.pdf` (mesmos argumentos).

Depois de mudar o código, gere o `.jar` de novo (`.\mvnw.cmd -DskipTests package`) antes do `rodar-api.ps1`.

`--oficial` = o operador confirma que o arquivo veio da fonte oficial. Sem isso, arquivo local conta como
fonte não confirmada.

Banco: H2 em arquivo (`dados/db`), compatível com PostgreSQL. Para usar Postgres, defina
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`.
Cópias dos PDFs: `dados/documentos/<sha256>.pdf`.

## O que o site faz com a API

| Rota | Quem chama | O que tem |
|---|---|---|
| `GET /api/v1/publico/vagas` | o site, até do navegador; **sem chave** | só os cargos **aprovados** na revisão, agrupados por concurso, com filtro por situação, formação e busca |
| `GET /api/v1/revisao/fila`, `/revisao/cargos/{id}` | o **backend** do site, com a chave | fila de cargos a revisar; cada cargo com o trecho do edital ao lado dos dados e a página do PDF onde está o requisito |
| `GET /api/v1/revisao/alteracoes` | backend do site | retificações, prorrogações e comunicados achados depois do edital, para aplicar ou descartar |
| `GET /api/v1/revisao/pistas` | backend do site | concursos em que o robô não achou o edital: informe o link ou envie o PDF |

- **Nada é público sem aprovação.** Aprovar publica o cargo; rejeitar ou desfazer a decisão (`PENDENTE`) tira da
  leitura pública (em até 1 minuto, por causa do cache).
- **Prévia do que vai ao ar.** O cargo para revisão traz cada dado exatamente como o público vai ler. O que estiver
  errado na extração pode ser **ocultado**: o público recebe "Confira no edital.".
- **Corrigir a classificação** do motor é possível, mas exige justificativa (pública) citando página e item do edital.
- A tela de revisão é do site. Enquanto ela não existe, o Swagger UI (`/swagger-ui.html`, botão **Authorize**) faz tudo.

**Chave da API:** o backend do site a envia no cabeçalho `X-API-Key`. Na primeira execução ela é gerada e gravada em
`dados/chave-api.txt` (nunca vai para o log). Para escolher a chave (mínimo de 24 caracteres), defina a variável de
ambiente `CONCURSOS_API_CHAVE`. A chave fica só no servidor do site: as rotas que a exigem não têm CORS.

## Robô de coleta

A cada hora (2 min depois de subir, depois a cada 1 h): lê as listagens do PCI (Sudeste e Nacional),
guarda cada concurso do RJ/nacional como **pista**, descarta os que a listagem mostra não ter nível técnico,
segue a notícia até o **site oficial** citado, procura o PDF do edital de abertura, baixa, analisa e põe os cargos
na fila de revisão. Nunca baixa a cópia do agregador e não ignora certificado inválido nem bloqueio (403):
nesses casos a pista fica em `/revisao/pistas` com o motivo. Configuração em `concursos.coleta` (`application.yml`).

Além do PCI, o robô lê:

- **Sites das bancas** (`concursos.coleta.bancas`): Selecon, IBADE, IAN, FGV, Cesgranrio e Instituto Consulplan
  (o CEPUERJ fica desligado: o Java não valida o certificado do site e os PDFs ficam numa pasta proibida a robôs).
  Cada banca tem a página que lista os concursos e a expressão que reconhece o link de cada um; o texto em
  volta do link (o "cartão") diz órgão, cidade, UF e situação. Ficam os concursos do RJ ainda abertos (municípios do
  estado, "RJ", "Rio de Janeiro" e os órgãos de `orgaos-rj`); os demais viram pista descartada com o motivo (fora do
  RJ, encerrado, antigo, vestibular/residência). A página do concurso na banca é fonte oficial: o edital é procurado
  nela e é ela que o acompanhamento revisita. AOCP e Cebraspe (lista montada por JavaScript), IDECAN e IBFC (recusam
  robôs) ficam de fora; Cesgranrio e Consulplan carregam os PDFs por JavaScript, então o robô descobre o concurso e
  pede o link ao revisor.
- **Diário Oficial da União** pelo [INLABS](https://inlabs.in.gov.br) (o site in.gov.br recusa robôs; o INLABS é o
  acesso automatizado oficial, com cadastro gratuito). Defina `CONCURSOS_INLABS_EMAIL` e `CONCURSOS_INLABS_SENHA`;
  sem elas, a fonte fica parada. O robô baixa cada edição da seção 3 uma vez, fica com os editais de abertura de
  concurso público com cargo técnico de órgão do RJ (ou com vaga no RJ) e procura o PDF do edital no site que o ato cita.
- **Portais de notícias** (`concursos.coleta.portais-de-noticias`), pelo feed RSS: Folha Dirigida (feeds do RJ e de
  concursos abertos), Concursos no Brasil (RJ) e JC Concursos. Ficam as notícias do RJ que falam de edital publicado ou
  inscrições; previsão, banca contratada, resultado, gabarito, inscritos e material de estudo viram pista descartada com
  o motivo. Como no PCI, o edital é procurado no site oficial citado no texto da notícia (propaganda e redes sociais
  são ignoradas). O QConcursos (www.qconcursos.com) fica de fora: bloqueia robôs com desafio da Cloudflare.

O robô respeita o **robots.txt** de todo site que acessa (guardado por 24 h). Quando o PDF está num endereço proibido
a robôs (ex.: os arquivos do Selecon), a pista fica como "Site não permite robôs" com o link do edital, para o revisor
baixar pelo navegador e enviar; ela não é tentada de novo. `concursos.coleta.sem-acesso-automatico` lista domínios
extras onde o robô não baixa nada.

## Acompanhamento (retificações, prazos, prova)

Depois de analisar um edital, o robô guarda a **página oficial** onde ele estava (a que lista as retificações) e volta
lá a cada 24 h enquanto o concurso está em andamento (até a data da prova). Cada PDF novo com cara de retificação,
errata, comunicado, prorrogação, adiamento ou suspensão é baixado **uma vez** e lido pelo motor
(`motor/alteracoes/AnalisadorAlteracoes`), que aponta, sempre com trecho e página:

| Proposta | O que o revisor faz |
|---|---|
| Documento novo | "Mostrar na página pública": o link entra em "Retificações e comunicados" do concurso |
| Prazo de inscrição / Data da prova | "Aplicar": atualiza o concurso (e a situação "Inscrições abertas" na página pública) |
| Situação (suspenso/cancelado) | "Aplicar": o concurso aparece como suspenso/cancelado |
| Onde se lê / leia-se, Nova redação | "Ciente" (leitura do que mudou) |
| Cargo publicado citado | "Voltar o cargo para revisão" (sai da página pública) ou "Ciente" |
| Sinal do agregador | o PCI mudou o prazo: nada é aplicado; a visita à página oficial é antecipada |

Nada muda na página pública sem "Aplicar". Um **edital consolidado/republicado** do mesmo concurso (mesmo órgão e nº)
vira a nova versão: os cargos são atualizados e a decisão do revisor continua valendo só para os cargos cujos dados
publicados não mudaram. Concurso sem página conhecida (ex.: edital enviado à mão): informe a página em
`/revisao/alteracoes` ou envie a retificação ali mesmo. Configuração em `concursos.acompanhamento`.

## API

Só `GET /api/v1/publico/**` é aberto. O resto exige a chave (`X-API-Key`), porque devolve cargos ainda não revisados.
Ex.: `curl -H "X-API-Key: <chave>" http://localhost:8080/api/v1/status`. Erros vêm em JSON (`application/problem+json`).
Referência completa e exemplos de consumo: [documentacao-api/API.md](documentacao-api/API.md).

| Método | Rota | |
|---|---|---|
| GET | `/api/v1/publico/vagas` | **sem chave:** vagas aprovadas, por concurso (`situacao=abertas\|todas`, `formacao`, `busca`) |
| GET | `/api/v1/revisao/fila?aba=`, `/revisao/cargos/{id}`, `/revisao/pistas`, `/revisao/alteracoes` | o que a tela de revisão do site mostra |
| POST | `/api/v1/alteracoes/{id}/aplicar` `/vista` `/descartar` `/reabrir-cargo` | decisão sobre uma retificação ou comunicado |
| PUT / POST | `/api/v1/concursos/{id}/pagina-oficial`, `/verificacoes`, `/documentos` | acompanhamento de um concurso |
| POST | `/api/v1/pistas/{id}/edital` (JSON ou multipart), `/pistas/{id}/descartar` | resolver ou descartar uma pista travada |
| POST | `/api/v1/documentos` | JSON `{"url": "...", "tipo": "EDITAL", "fonteOficial": false}` ou multipart (`arquivo`, `urlOrigem`) |
| GET | `/api/v1/cargos` | busca com filtros; `formato=markdown` devolve o relatório |
| GET | `/api/v1/cargos/{id}` | detalhe com a análise completa |
| GET | `/api/v1/cargos/{id}/evidencias` | campo a campo: página, item, trecho |
| POST | `/api/v1/cargos/{id}/revisao` | `{"decisao": "APROVADA", "classificacao": null, "justificativa": null, "ocultar": ["TAXA"], "observacao": "..."}`; também `REJEITADA` e `PENDENTE` |
| GET | `/api/v1/concursos`, `/{id}`, `/{id}/documentos`, `/{id}/alteracoes` | |
| GET | `/api/v1/documentos/{id}/arquivo` | cópia guardada do PDF (use `#page=N`) |
| GET | `/api/v1/documentos/{id}/analise` | JSON completo da análise |
| POST / GET | `/api/v1/admin/coletas` | dispara uma coleta agora / últimas execuções |
| GET | `/api/v1/pistas?status=` | pistas do robô (NOVA, DESCARTADA, SEM_FONTE_OFICIAL, EDITAL_NAO_ENCONTRADO, ERRO_ACESSO, BLOQUEADA, ANALISADA) |
| POST | `/api/v1/pistas/{id}/edital` | `{"url": "...", "fonteOficial": true}` para uma pista que o robô não resolveu |
| GET | `/api/v1/formacoes`, `/api/v1/status` | |

Filtros de `/cargos`: `uf, municipio, formacao, especialidade, salarioMin, salarioMax, conselho
(CRT_RJ|CRT|CFT|CREA|MTE|OUTRO|GENERICO|NENHUM), aceitaCrt, incluirConselhoGenerico, semConselho,
status (INSCRICOES_ABERTAS…), cadastroReserva, vagasImediatas, banca, orgao, esfera, inscricaoAte,
classificacao (padrão COMPATIVEL,POSSIVELMENTE_COMPATIVEL; TODAS)`.

Exemplo da especificação:

```
GET /api/v1/cargos?uf=RJ&formacao=eletrotecnica&salarioMin=4000&status=INSCRICOES_ABERTAS&formato=markdown
```

## Estrutura

```
motor/            Java puro, sem Spring
  texto/          normalização (mesmo comprimento do original), estrutura de itens e anexos
  leitor/         PDF → texto por página (PDFBox)
  catalogo/       formações técnicas e sinônimos
  extracao/       cargos, requisito, conselho, valores do cargo, dados do concurso
  evidencia/      verificador: trecho existe na página? valor sai do trecho?
  classificacao/  regras + justificativa por template
  alteracoes/     retificações e comunicados: prazo, prova, suspensão, onde se lê/leia-se, nova redação
coleta/           robô: PCI, bancas, portais de notícias e DOU → pista → site oficial → edital (respeitando robots.txt); acompanhamento
fontes/           oficial × agregador (por domínio)
documentos/       download e armazenamento por sha256
persistencia/     JdbcClient + Flyway (db/migration)
servico/          ingestão, consulta, revisão (o que pode ser publicado); ServicoVagasPublicas (leitura aberta, só
                  aprovados) separado de ServicoVisaoRevisor e ServicoAlteracoes (só com chave)
api/              REST: VagasPublicasController é o único sem chave; erros em problem+json (TratadorErros)
config/           beans, agendamento, segurança (chave de API e CORS), OpenAPI
relatorio/        markdown no formato da especificação
cli/              `analisar`
```

## Ainda não feito

- **Base de editais reais:** UFF 96/2025, UERJ 01/CEPUERJ/2026, Tanguá 001/2026 e Engenheiro Paulo de
  Frontin 01/2026 (`src/test/resources/editais-reais`, testes em `EditaisReaisTest`). Meta: 30 a 50 editais
  do RJ, medindo precisão e recall. Todo erro encontrado em edital real vira teste.
- **Tabelas de cargos** (ex.: Tanguá): vagas, carga horária e taxa na mesma linha do cargo ainda não são
  lidas por coluna, e o trecho pode pegar o fim da linha anterior. A revisão mostra isso e permite ocultar.
- **Anexo em PDF separado:** quando o quadro de vagas vem em outro arquivo (ex.: UFF), as vagas
  ainda não são cruzadas com o edital.
- **OCR** para PDFs escaneados: hoje o documento é marcado e os cargos ficam NÃO FOI POSSÍVEL CONFIRMAR.
- **IA (Claude) como extrator assistido**, sempre passando pelo verificador de evidências.
- **Retificação que muda valores de um cargo** (ex.: nova tabela de remuneração): o motor mostra a nova redação e
  avisa que o cargo publicado foi citado, mas não reescreve o salário do cargo; isso acontece quando sai o edital
  consolidado. Até lá, o revisor pode ocultar o dado ou devolver o cargo para a fila.
- **Página oficial de concursos enviados à mão** precisa ser informada pelo revisor para o acompanhamento funcionar.
- **Diário Oficial do Estado (DOERJ) e diários municipais**: ainda não lidos. O DOU depende do cadastro no INLABS
  (testado só com pacotes sintéticos no formato do INLABS até o cadastro existir).
- **Bancas que montam a página por JavaScript** (AOCP, Cebraspe, PDFs da Cesgranrio e do Consulplan): só pelo PCI,
  pelos portais de notícias ou com o link informado pelo revisor.
- **Portais de notícias**: o filtro de etapa olha a manchete; uma notícia de edital aberto com manchete atípica pode
  ser descartada (o motivo fica na pista) — o mesmo concurso costuma chegar também pelo PCI ou pela banca.
- **Alertas**, **início automático no Windows** e **publicação na internet** (antes: HTTPS e, na frente da API, limite
  de requisições por IP na leitura pública).
- **Tela de revisão no site:** a API já entrega tudo (fila, cargo com evidências, alterações, pistas); a interface é do site.
