package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.config.ConcursosProperties;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio;
import br.gov.crtrj.concursos.persistencia.ExecucaoColetaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.PistaLinha;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.StatusPista;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uma rodada do robô: lê as fontes de descoberta (PCI, sites das bancas, Diário Oficial, portais de
 * notícias), registra
 * pistas novas e processa algumas pendentes — da pista até o edital oficial, que é analisado e vai
 * para a fila de revisão.
 */
@Service
public class ServicoColeta {

    private static final Logger log = LoggerFactory.getLogger(ServicoColeta.class);

    public record ResumoExecucao(long id, int pistasLidas, int pistasNovas, int editaisAnalisados, int erros,
                                 List<String> eventos) {
    }

    private final List<FonteDescoberta> fontesDescoberta;
    private final ClienteWeb web;
    private final LocalizadorEditalOficial localizador;
    private final ServicoIngestao ingestao;
    private final PistaRepositorio pistas;
    private final ExecucaoColetaRepositorio execucoes;
    private final ConcursoRepositorio concursos;
    private final DocumentoEncontradoRepositorio encontrados;
    private final ServicoAcompanhamento acompanhamento;
    private final ConcursosProperties.Coleta config;
    private final Clock relogio;
    private final AtomicBoolean rodando = new AtomicBoolean();

    public ServicoColeta(List<FonteDescoberta> fontesDescoberta, ClienteWeb web, LocalizadorEditalOficial localizador, ServicoIngestao ingestao,
                         PistaRepositorio pistas, ExecucaoColetaRepositorio execucoes, ConcursoRepositorio concursos,
                         DocumentoEncontradoRepositorio encontrados, ServicoAcompanhamento acompanhamento,
                         ConcursosProperties props, Clock relogio) {
        this.concursos = concursos;
        this.encontrados = encontrados;
        this.acompanhamento = acompanhamento;
        this.fontesDescoberta = List.copyOf(fontesDescoberta);
        this.web = web;
        this.localizador = localizador;
        this.ingestao = ingestao;
        this.pistas = pistas;
        this.execucoes = execucoes;
        this.config = props.coleta();
        this.relogio = relogio;
    }

    public boolean emAndamento() {
        return rodando.get();
    }

    /** Executa uma rodada; vazio se outra já estiver em andamento. */
    public Optional<ResumoExecucao> executar() {
        if (!rodando.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            return Optional.of(rodada());
        } finally {
            rodando.set(false);
        }
    }

    private ResumoExecucao rodada() {
        long id = execucoes.iniciar(agora());
        List<String> eventos = new ArrayList<>();
        int lidas = 0;
        int novas = 0;
        int analisados = 0;
        int erros = 0;

        for (FonteDescoberta fonte : fontesDescoberta) {
            List<String> avisos = new ArrayList<>();
            try {
                List<PistaEncontrada> encontradas = fonte.listar(url -> pistas.porUrl(url).isPresent(), avisos);
                int novasDaFonte = 0;
                int aProcessar = 0;
                for (PistaEncontrada p : encontradas) {
                    if (registrar(p)) {
                        novasDaFonte++;
                        aProcessar += p.motivoDescarte() == null ? 1 : 0;
                    }
                }
                lidas += encontradas.size();
                novas += novasDaFonte;
                if (!encontradas.isEmpty() && !(fonte instanceof FontePci)) {
                    eventos.add(fonte.nome() + ": " + encontradas.size() + " concurso(s) lido(s), " + novasDaFonte
                            + " novo(s), " + aProcessar + " do RJ para analisar");
                }
            } catch (IOException | RuntimeException e) {
                erros++;
                eventos.add("Falha ao ler " + fonte.nome() + ": " + e.getMessage());
                log.warn("Falha ao ler a fonte {}", fonte.nome(), e);
            }
            erros += avisos.size();
            eventos.addAll(avisos);
        }

        for (PistaLinha pista : pistas.pendentes(agora(), config.horasEntreTentativas(), config.maxTentativas(),
                config.maxEditaisPorExecucao())) {
            StatusPista status = processar(pista, eventos);
            if (status == StatusPista.ANALISADA) {
                analisados++;
            } else if (status == StatusPista.ERRO_ACESSO) {
                erros++;
            }
        }

        // concursos já analisados: volta à página oficial atrás de retificações e comunicados
        try {
            ServicoAcompanhamento.Resumo acomp = acompanhamento.acompanharPendentes();
            eventos.addAll(acomp.eventos());
            if (acomp.concursosVisitados() > 0) {
                eventos.add("Acompanhamento: " + acomp.concursosVisitados() + " concurso(s) visitado(s), "
                        + acomp.documentosNovos() + " documento(s) novo(s), " + acomp.propostas()
                        + " alteração(ões) para revisar");
            }
        } catch (RuntimeException e) {
            erros++;
            eventos.add("Falha no acompanhamento: " + e.getMessage());
            log.warn("Falha no acompanhamento", e);
        }

        String resumo = String.join("\n", eventos);
        execucoes.terminar(id, agora(), lidas, novas, analisados, erros, resumo);
        log.info("Coleta #{}: {} pistas lidas, {} novas, {} editais analisados, {} erros", id, lidas, novas, analisados, erros);
        return new ResumoExecucao(id, lidas, novas, analisados, erros, eventos);
    }

    /** Guarda a pista se for nova; se já existir, só atualiza o que a listagem mostra. */
    private boolean registrar(PistaEncontrada p) {
        Optional<PistaLinha> existente = pistas.porUrl(p.url());
        if (existente.isPresent()) {
            PistaLinha antes = existente.get();
            pistas.atualizarListagem(antes.id(), p.titulo(), p.vagas(), p.cargos(), p.escolaridade(), p.inscricaoAte());
            // a listagem mudou o prazo de um concurso já analisado: provável prorrogação a confirmar no site oficial
            if (antes.concursoId() != null && antes.inscricaoAte() != null && p.inscricaoAte() != null
                    && !antes.inscricaoAte().equals(p.inscricaoAte())) {
                acompanhamento.registrarSinal(antes.concursoId(), "Prazo de inscrição (agregador)",
                        "inscrições até " + antes.inscricaoAte(), "inscrições até " + p.inscricaoAte(), p.fonte());
            }
            return false;
        }
        boolean interessa = p.motivoDescarte() == null;
        LocalDateTime agora = agora();
        pistas.inserir(new PistaLinha(null, p.fonte(), p.url(), limitar(p.titulo(), 700), limitar(p.orgao(), 500), p.uf(),
                limitar(p.vagas(), 300), limitar(p.cargos(), 1000), limitar(p.escolaridade(), 200),
                limitar(p.inscricaoAte(), 40), (interessa ? StatusPista.NOVA : StatusPista.DESCARTADA).name(),
                p.motivoDescarte(), null, null, null, 0, agora, agora, p.texto()));
        return true;
    }

    /**
     * Da pista ao edital, conforme a origem: notícia de agregador ou portal (o edital está num site
     * citado), ato de diário oficial (idem, a partir do texto guardado) ou página do concurso na banca.
     */
    private LocalizadorEditalOficial.Resultado localizar(PistaLinha pista) throws IOException {
        if (FontePci.NOME.equals(pista.fonte()) || localizador.ehNoticia(pista.url())) {
            return localizador.localizar(pista.url(), pista.orgao());
        }
        if (pista.texto() != null) {
            return localizador.localizarPorTexto(pista.texto(), pista.orgao());
        }
        return localizador.localizarNaPagina(pista.url(), pista.titulo());
    }

    private StatusPista processar(PistaLinha pista, List<String> eventos) {
        String rotulo = pista.orgao() + " (" + pista.url() + ")";
        String urlEdital = null;
        try {
            LocalizadorEditalOficial.Resultado r = localizar(pista);
            if (!r.encontrado()) {
                StatusPista status = StatusPista.valueOf(r.situacao().name());
                pistas.registrarResultado(pista.id(), status, r.motivo(), r.paginaOficial(), null, null, agora());
                eventos.add(status + ": " + rotulo + " — " + r.motivo());
                return status;
            }
            urlEdital = r.urlEdital();
            // o PDF pode estar num site (ou pasta) que não permite robôs mesmo com a página liberada
            web.verificarPermissao(urlEdital);
            ServicoIngestao.Resumo resumo = ingestao.ingerirUrl(urlEdital, TipoDocumento.EDITAL, false);
            // r.motivo() avisa quando o edital veio de site fora da lista oficial (ex.: banca nova)
            pistas.registrarResultado(pista.id(), StatusPista.ANALISADA, r.motivo(), r.urlEdital(), resumo.documentoId(),
                    resumo.concursoId(), agora());
            // acompanhamento: a página que lista o edital e os documentos posteriores já vistos nela
            if (r.paginaOficial() != null) {
                concursos.definirPaginaOficial(resumo.concursoId(), r.paginaOficial(), false);
            }
            for (LocalizadorEditalOficial.DocumentoNaPagina outro : r.outrosDocumentos()) {
                encontrados.registrarSeNovo(resumo.concursoId(), outro.url(), outro.texto(), agora());
            }
            if (resumo.jaAnalisado()) {
                // notícia nova sobre concurso conhecido (ex.: "prorroga inscrições"): visita a página oficial logo
                concursos.marcarParaChecar(resumo.concursoId());
            }
            eventos.add("ANALISADA: " + rotulo + " — " + resumo.cargos().size() + " cargo(s) técnico(s), edital "
                    + r.urlEdital() + (r.fonteConfirmada() ? "" : " (fonte a confirmar)"));
            return StatusPista.ANALISADA;
        } catch (ClienteWeb.AcessoNaoPermitido e) {
            // guarda o link do edital (quando já achado) para o revisor baixar pelo navegador
            pistas.registrarResultado(pista.id(), StatusPista.BLOQUEADA, e.getMessage(), urlEdital, null, null, agora());
            eventos.add("BLOQUEADA: " + rotulo + " — " + e.getMessage());
            return StatusPista.BLOQUEADA;
        } catch (IOException | RuntimeException e) {
            pistas.registrarResultado(pista.id(), StatusPista.ERRO_ACESSO, e.getMessage(), null, null, null, agora());
            eventos.add("ERRO_ACESSO: " + rotulo + " — " + e.getMessage());
            log.warn("Falha ao processar pista {}", pista.url(), e);
            return StatusPista.ERRO_ACESSO;
        }
    }

    /** Roda uma coleta fora da thread da requisição; false se já houver uma em andamento. */
    public boolean dispararEmSegundoPlano() {
        if (emAndamento()) {
            return false;
        }
        Thread.ofVirtual().name("coleta-manual").start(this::executar);
        return true;
    }

    /**
     * Edital de uma pista que o robô não resolveu, informado pelo revisor: um link, ou o próprio PDF
     * (sites com certificado inválido ou que bloqueiam robôs).
     *
     * @param pdf null para baixar de {@code url}; senão {@code url} é só a origem declarada (opcional)
     */
    public ServicoIngestao.Resumo informarEdital(long pistaId, String url, byte[] pdf, String nomeArquivo,
                                                 boolean fonteOficial) throws IOException {
        pistas.porId(pistaId).orElseThrow(() -> new NoSuchElementException("Pista " + pistaId + " não encontrada"));
        ServicoIngestao.Resumo resumo = pdf != null
                ? ingestao.ingerirArquivo(pdf, nomeArquivo, url, TipoDocumento.EDITAL, fonteOficial)
                : ingestao.ingerirUrl(url, TipoDocumento.EDITAL, fonteOficial);
        pistas.registrarResultado(pistaId, StatusPista.ANALISADA,
                pdf != null ? "Edital enviado manualmente (arquivo)." : "Edital informado manualmente.", url,
                resumo.documentoId(), resumo.concursoId(), agora());
        return resumo;
    }

    /** O revisor decidiu que a pista não interessa: o robô para de tentar. */
    public void descartar(long pistaId) {
        pistas.porId(pistaId).orElseThrow(() -> new NoSuchElementException("Pista " + pistaId + " não encontrada"));
        pistas.descartar(pistaId, "Descartada na revisão.", agora());
    }

    private static String limitar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(relogio);
    }
}
