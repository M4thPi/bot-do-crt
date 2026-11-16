# Implantação — do Mac ao site no ar

Este guia é para quem vai rodar a API fora do computador onde ela foi feita: no Mac, para conhecer e testar, e
num servidor, para ficar no ar e ser consumida pelo site (o que o site chama e como: [../documentacao-api/API.md](../documentacao-api/API.md)). Como o robô funciona por dentro: [COMO-FUNCIONA.md](COMO-FUNCIONA.md).

## O que é, do ponto de vista da hospedagem

- **Um único programa Java** (`target/concursos-tecnicos.jar`). Ele é ao mesmo tempo a API e o robô
  (não há tela: a interface é do site). Não há outro serviço para instalar além do Java (e, em produção, do banco PostgreSQL).
- **Precisa ficar ligado o tempo todo**: o robô roda a cada hora e o acompanhamento a cada 24 h. Serviço que desliga o
  programa quando ninguém acessa não serve.
- **Precisa de saída para a internet** (acessa PCI, bancas, portais, INLABS e os sites oficiais).
- **Guarda dados**: o banco e as cópias dos PDFs dos editais (a prova de cada dado). Isso precisa de disco que não se
  apague a cada atualização, e de backup.
- **Recursos**: 1 GB de memória e 1 CPU bastam; o disco cresce com os PDFs (alguns MB por edital).

## No Mac (para ver e testar)

1. **JDK 25**: baixe o instalador `.pkg` do Temurin 25 em [adoptium.net](https://adoptium.net/temurin/releases/?version=25)
   — escolha **macOS**, e **aarch64** se o Mac tiver chip Apple (M1, M2, M3...) ou **x64** se for Intel. Confira no
   Terminal: `java -version` deve mostrar `25`.
2. **Git**: no Terminal, `git --version` (se não tiver, o Mac oferece instalar as ferramentas de linha de comando).
3. **Baixar e rodar**:

   ```sh
   git clone <endereço do repositório>
   cd concursos-tecnicos
   ./mvnw package        # compila e roda os testes (a 1ª vez baixa as dependências)
   ./rodar-api.sh        # sobe em http://localhost:8080
   ```

4. A API está em `http://localhost:8080` e a documentação interativa em `http://localhost:8080/swagger-ui.html`. A chave
   fica em `dados/chave-api.txt` (criada na 1ª execução). Teste:

   ```sh
   curl http://localhost:8080/api/v1/publico/vagas                                   # aberto
   curl -H "X-API-Key: $(cat dados/chave-api.txt)" http://localhost:8080/api/v1/status # com a chave
   ```
5. Para analisar um PDF sem subir a API: `./analisar.sh ~/Downloads/edital.pdf`.

Os scripts `.sh` são os equivalentes Mac/Linux dos `.ps1` do Windows. Para abrir o código, qualquer IDE Java serve
(IntelliJ IDEA abre o `pom.xml` direto).

## No servidor

### Primeiro: que tipo de hospedagem o site usa?

| Hospedagem do site | O sistema roda nela? | Caminho |
|---|---|---|
| **Hospedagem compartilhada** (planos de site com cPanel, WordPress, PHP) | **Não** — esses planos não mantêm um programa Java ligado | O site continua onde está. O sistema sobe num servidor à parte (VPS ou plataforma de containers) e fica num **subdomínio**, ex.: `vagas.seusite.org.br`, com link no menu do site |
| **VPS ou servidor Linux próprio** (VPS da Hostinger/Locaweb, DigitalOcean, AWS EC2, servidor do órgão) | **Sim** | Docker ou Java 25 instalado, com HTTPS na frente (abaixo) |
| **Plataforma de containers** (Render, Railway, Fly.io, Azure App Service...) | **Sim**, com disco persistente e PostgreSQL | Usa o `Dockerfile` do repositório; plano que fique sempre ligado |

A API fica num **subdomínio** (ex.: `api.seusite.org.br`) e o site a consome:

- a **leitura pública** (`GET /api/v1/publico/vagas`, sem chave) pode ser chamada do navegador, desde que a origem do
  site esteja em `CONCURSOS_ORIGENS_PERMITIDAS`, ou do servidor do site;
- tudo o mais (revisão, alterações, pistas) exige a **chave de API** e só pode ser chamado pelo **servidor** do site, que a
  guarda. A chave nunca vai para o navegador, e essas rotas não aceitam CORS de propósito.

### Configuração (variáveis de ambiente)

Nada de senha vai no código: tudo é passado por variável de ambiente.

| Variável | Para quê |
|---|---|
| `CONCURSOS_API_CHAVE` | chave que o servidor do site envia em `X-API-Key` (mínimo de 24 caracteres; ex.: `openssl rand -base64 32`). Sem ela, uma é gerada em `dados/chave-api.txt` |
| `CONCURSOS_ORIGENS_PERMITIDAS` | origens do site que leem `/api/v1/publico/**` do navegador, separadas por vírgula (ex.: `https://seusite.org.br`). Vazio = nenhuma; `*` libera todas |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | banco PostgreSQL, ex.: `jdbc:postgresql://localhost:5432/concursos` (sem elas, usa o H2 em arquivo em `dados/db`) |
| `CONCURSOS_INLABS_EMAIL`, `CONCURSOS_INLABS_SENHA` | leitura do Diário Oficial da União (cadastro gratuito no INLABS) |
| `CONCURSOS_DOWNLOAD_USER_AGENT` | como o robô se apresenta aos sites; ponha um contato, ex.: `ConcursosTecnicosBot/1.0 (+https://seusite.org.br/contato)` |
| `CONCURSOS_ARMAZENAMENTO` | pasta das cópias dos PDFs (padrão `./dados/documentos`) |
| `SERVER_FORWARD_HEADERS_STRATEGY=native` | quando há HTTPS na frente (proxy): os endereços gerados passam a ser `https://` |
| `SPRINGDOC_SWAGGER_UI_ENABLED=false` | desliga a documentação interativa (`/swagger-ui.html`) em produção, se preferir; `SPRINGDOC_API_DOCS_ENABLED=false` desliga também o `/v3/api-docs` |
| `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` | em container, deixa o Java usar 75% da memória (o padrão é 25%, pouco para PDFs grandes) |

O banco é criado e atualizado sozinho na subida (Flyway): numa versão nova do código, basta reiniciar.

### Opção A — Docker (VPS ou plataforma de containers)

```sh
docker build -t concursos-tecnicos .
docker run -d --name concursos --restart unless-stopped -p 127.0.0.1:8080:8080 \
  -v concursos-dados:/app/dados \
  -e CONCURSOS_API_CHAVE='troque-por-uma-chave-longa-e-aleatoria' \
  -e CONCURSOS_ORIGENS_PERMITIDAS=https://seusite.org.br \
  -e SERVER_FORWARD_HEADERS_STRATEGY=native \
  -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 \
  concursos-tecnicos
```

O volume `concursos-dados` guarda banco, PDFs e a chave entre atualizações. Para atualizar: `git pull`, `docker build`
de novo, remover o container antigo e rodar o mesmo `docker run`.

### Opção B — Java direto num servidor Linux

1. Instale o JDK 25 (Temurin, pelo gerenciador de pacotes da distribuição ou pelo site da Adoptium).
2. `git clone`, `./mvnw -DskipTests package` e copie `target/concursos-tecnicos.jar` para `/opt/concursos/`.
3. Crie o serviço `/etc/systemd/system/concursos.service` para ele subir junto com o servidor e reiniciar se cair:

   ```ini
   [Unit]
   Description=API de concursos para Técnicos Industriais
   After=network-online.target

   [Service]
   User=concursos
   WorkingDirectory=/opt/concursos
   Environment=CONCURSOS_API_CHAVE=troque-por-uma-chave-longa-e-aleatoria
   Environment=CONCURSOS_ORIGENS_PERMITIDAS=https://seusite.org.br
   Environment=SERVER_FORWARD_HEADERS_STRATEGY=native
   ExecStart=/usr/bin/java -jar /opt/concursos/concursos-tecnicos.jar
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```

   Depois: `sudo systemctl enable --now concursos`.

### HTTPS e subdomínio (nas duas opções)

O programa escuta em `http://localhost:8080`; quem atende a internet é um proxy com HTTPS. Com o
[Caddy](https://caddyserver.com), que emite o certificado sozinho, a configuração inteira é:

```
api.seusite.org.br {
    reverse_proxy localhost:8080
}
```

No painel de DNS do domínio, crie um registro `A` para `api` apontando para o IP do servidor. (Nginx ou o proxy da
própria plataforma também servem.)

### Backup

Guarde diariamente o banco (dump do PostgreSQL, ou a pasta `dados/db` com o programa parado) e a pasta
`dados/documentos`. Sem os PDFs, a evidência de cada dado publicado se perde.

## Antes de abrir para a internet

- [ ] HTTPS funcionando no subdomínio (acima).
- [ ] `CONCURSOS_API_CHAVE` definida e guardada só no servidor do site (a API recusa chave com menos de 24 caracteres,
      o que inviabiliza adivinhar por tentativa e erro).
- [ ] `CONCURSOS_ORIGENS_PERMITIDAS` com o endereço do site (sem isso o navegador não lê `/api/v1/publico/vagas`).
- [ ] **Limite de requisições por IP no proxy** para `/api/v1/publico/**`, que é aberta (no Caddy, o módulo
      `rate_limit`; no Nginx, `limit_req`). A resposta já vem com `Cache-Control: max-age=60`: o site ou uma CDN
      deve aproveitá-lo.
- [ ] PostgreSQL: o sistema foi testado com o H2 em modo compatível com PostgreSQL; rodar uma coleta completa num
      PostgreSQL real antes de usar em produção.
- [ ] Catálogo de formações (`src/main/resources/catalogo/formacoes.json`) validado pelo CRT-RJ.
- [ ] Definido quem revisa e aprova as vagas, e a tela de revisão do site feita (até lá, o Swagger UI com a chave).
- [ ] `CONCURSOS_DOWNLOAD_USER_AGENT` com um contato.
- [ ] Backup diário configurado.

O `Dockerfile` segue o modelo padrão para aplicações Spring Boot, mas não foi testado na máquina onde o sistema foi
desenvolvido (ela não tem Docker); o `.jar` e os scripts `.sh` foram testados.
