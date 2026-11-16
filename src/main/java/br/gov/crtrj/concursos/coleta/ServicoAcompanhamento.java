package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.config.ConcursosProperties;
import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.motor.alteracoes.AnalisadorAlteracoes;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.AlteracaoLinha;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.Status;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.Tipo;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.Acompanhamento;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio.DocumentoEncontradoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio.Situacao;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
import br.gov.crtrj.concursos.servico.ServicoRevisao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Acompanhamento de concursos já analisados: volta à página oficial, baixa os documentos novos
 * (retificação, errata, comunicado, prorrogação) e transforma o que eles mudam em propostas com
 * trecho e página. Nada muda na página pública até o revisor aplicar.
 */
@Service
public class ServicoAcompanhamento {

    private static final Logger log = LoggerFactory.getLogger(ServicoAcompanhamento.class);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern RETIFICACAO = Pattern.compile("(?i)retifica|errata|aditivo|consolidad|republica");
    private static final Pattern CONSOLIDADO = Pattern.compile("(?i)consolidad|republica");

    public record Resumo(int concursosVisitados, int documentosNovos, int propostas, List<String> eventos) {
    }

    private final LocalizadorEditalOficial localizador;
    private final ClienteWeb web;
    private final BaixadorDocumentos baixador;
    private final ValidadorFontes fontes;
    private final ServicoIngestao ingestao;
    private final ServicoRevisao revisao;
    private final AnalisadorAlteracoes analisador;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final DocumentoEncontradoRepositorio encontrados;
    private final AlteracaoRepositorio alteracoes;
    private final CargoRepositorio cargos;
    private final ConcursosProperties.Acompanhamento config;
    private final Clock relogio;

    public ServicoAcompanhamento(LocalizadorEditalOficial localizador, ClienteWeb web, BaixadorDocumentos baixador,
                                 ValidadorFontes fontes, ServicoIngestao ingestao, ServicoRevisao revisao,
                                 AnalisadorAlteracoes analisador, ConcursoRepositorio concursos,
                                 DocumentoRepositorio documentos, DocumentoEncontradoRepositorio encontrados,
                                 AlteracaoRepositorio alteracoes, CargoRepositorio cargos, ConcursosProperties props,
                                 Clock relogio) {
        this.localizador = localizador;
        this.web = web;
        this.baixador = baixador;
        this.fontes = fontes;
        this.ingestao = ingestao;
        this.revisao = revisao;
        this.analisador = analisador;
        this.concursos = concursos;
        this.documentos = documentos;
        this.encontrados = encontrados;
        this.alteracoes = alteracoes;
        this.cargos = cargos;
        this.config = props.acompanhamento();
        this.relogio = relogio;
    }

    // ================================================================== visitas

    /** Uma rodada: concursos em andamento com visita vencida (ou pedida por algum sinal). */
    public Resumo acompanharPendentes() {
        LocalDateTime agora = agora();
        LocalDate hoje = agora.toLocalDate();
        List<Acompanhamento> lista = concursos.aAcompanhar(agora.minus(config.intervalo()), hoje.minusDays(1),
                hoje.minusDays(config.diasAposInscricaoSemProva()), config.maxConcursosPorExecucao());
        Contagem total = new Contagem();
        for (Acompanhamento k : lista) {
            total.somar(acompanhar(k));
        }
        return new Resumo(lista.size(), total.documentos, total.propostas, total.eventos);
    }

    /** Visita já, a pedido do revisor. */
    public Resumo acompanharAgora(long concursoId) {
        Acompanhamento k = concursos.acompanhamento(concursoId)
                .orElseThrow(() -> new NoSuchElementException("Concurso " + concursoId + " não encontrado"));
        if (k.paginaOficial() == null) {
            throw new IllegalArgumentException("Informe antes a página oficial do concurso.");
        }
        Contagem c = acompanhar(k);
        return new Resumo(1, c.documentos, c.propostas, c.eventos);
    }

    private static final class Contagem {
        int documentos;
        int propostas;
        final List<String> eventos = new ArrayList<>();

        void somar(Contagem outra) {
            documentos += outra.documentos;
            propostas += outra.propostas;
            eventos.addAll(outra.eventos);
        }
    }

    /** Uma visita por vez: a rodada do robô e o "verificar agora" não baixam o mesmo documento duas vezes. */
    private synchronized Contagem acompanhar(Acompanhamento k) {
        Contagem c = new Contagem();
        LocalDateTime agora = agora();
        try {
            Set<String> jaGuardados = documentos.listarPorConcurso(k.id()).stream()
                    .map(DocumentoRepositorio.DocumentoLinha::url).filter(Objects::nonNull).collect(Collectors.toSet());
            for (LocalizadorEditalOficial.DocumentoNaPagina d
                    : localizador.documentosNaPagina(k.paginaOficial(), k.numeroEdital(), k.orgao())) {
                if (!jaGuardados.contains(d.url()) && encontrados.registrarSeNovo(k.id(), d.url(), d.texto(), agora)) {
                    c.documentos++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // a página falhou, mas documentos já conhecidos e ainda não baixados continuam na fila
            c.eventos.add("Acompanhamento de " + k.nome() + ": página oficial indisponível (" + e.getMessage() + ")");
            log.warn("Falha ao visitar a página oficial de {}", k.nome(), e);
        }
        for (DocumentoEncontradoLinha d : encontrados.aProcessar(k.id(), config.maxTentativas(),
                config.maxDocumentosPorConcurso())) {
            c.propostas += processar(k, d, c.eventos);
        }
        concursos.registrarAcompanhamento(k.id(), agora);
        return c;
    }

    private int processar(Acompanhamento k, DocumentoEncontradoLinha d, List<String> eventos) {
        try {
            web.verificarPermissao(d.url());
            BaixadorDocumentos.Download download = baixador.baixar(d.url());
            if (!LeitorPdf.pareceSerPdf(download.conteudo())) {
                encontrados.registrarResultado(d.id(), Situacao.IGNORADO, "O link não devolveu um PDF.", null, agora());
                return 0;
            }
            int n = analisarDocumento(k, download.conteudo(), download.urlFinal(), d.texto(),
                    fontes.classificar(download.urlFinal()), d.id());
            eventos.add(k.nome() + ": documento novo (" + d.texto() + "), " + n + " proposta(s) para revisão");
            return n;
        } catch (ClienteWeb.AcessoNaoPermitido e) {
            // não adianta tentar de novo: vai para Alterações, para o revisor abrir no navegador e enviar o arquivo
            encontrados.registrarResultado(d.id(), Situacao.IGNORADO, e.getMessage(), null, agora());
            if (!alteracoes.existe(k.id(), Tipo.DOCUMENTO_BLOQUEADO, d.url())) {
                alteracoes.inserir(new AlteracaoLinha(null, k.id(), null, "Documento novo que o robô não pode baixar",
                        null, d.texto(), agora(), Tipo.DOCUMENTO_BLOQUEADO.name(), Status.PENDENTE.name(), d.url(),
                        null, null, null, null));
            }
            eventos.add(k.nome() + ": documento novo que o robô não pode baixar (" + d.texto() + "): " + e.getMessage());
            return 1;
        } catch (IOException | RuntimeException e) {
            encontrados.registrarResultado(d.id(), Situacao.ERRO, e.getMessage(), null, agora());
            eventos.add(k.nome() + ": falha ao baixar " + d.url() + " (" + e.getMessage() + ")");
            log.warn("Falha ao processar documento {}", d.url(), e);
            return 0;
        }
    }

    /**
     * Retificação ou comunicado informado pelo revisor (link ou arquivo) para um concurso.
     *
     * @param pdf null para baixar de {@code url}
     */
    public int informarDocumento(long concursoId, String url, byte[] pdf, String nomeArquivo, boolean fonteOficial)
            throws IOException {
        Acompanhamento k = concursos.acompanhamento(concursoId)
                .orElseThrow(() -> new NoSuchElementException("Concurso " + concursoId + " não encontrado"));
        byte[] conteudo = pdf;
        String origem = url;
        if (conteudo == null) {
            BaixadorDocumentos.Download d = baixador.baixar(url);
            conteudo = d.conteudo();
            origem = d.urlFinal();
        }
        ValidadorFontes.TipoFonte fonte = fonteOficial ? ValidadorFontes.TipoFonte.CONFIRMADA_MANUALMENTE
                : origem == null ? ValidadorFontes.TipoFonte.ENVIO_MANUAL : fontes.classificar(origem);
        String nome = nomeArquivo != null ? nomeArquivo : origem != null ? nomeDaUrl(origem) : "documento.pdf";
        return analisarDocumento(k, conteudo, origem, nome, fonte, null);
    }

    /** @param encontradoId documento visto na página (null quando veio do revisor) */
    private synchronized int analisarDocumento(Acompanhamento k, byte[] pdf, String url, String textoDoLink,
                                               ValidadorFontes.TipoFonte fonte, Long encontradoId) throws IOException {
        List<CargoLinha> publicados = cargos.listarPorConcurso(k.id()).stream()
                .filter(c -> "APROVADA".equals(c.revisaoStatus())).toList();
        AnalisadorAlteracoes.Resultado r = analisador.analisarPdf(pdf,
                publicados.stream().map(CargoLinha::nome).distinct().toList());
        String nome = r.titulo() != null ? r.titulo() : textoDoLink;

        // edital consolidado/republicado do mesmo concurso: vira a nova versão (valores corrigidos nos cargos)
        if (CONSOLIDADO.matcher(nome + " " + textoDoLink).find()) {
            Optional<ServicoIngestao.Resumo> versao = ingestao.ingerirNovaVersao(k.id(), pdf, url, nome, fonte);
            if (versao.isPresent()) {
                long documentoId = versao.get().documentoId();
                alteracoes.inserir(proposta(k.id(), documentoId, Tipo.NOVO_DOCUMENTO, "Novo documento", null,
                        nome + " — nova versão do edital: cargos atualizados; os que mudaram voltaram para a fila",
                        String.valueOf(documentoId), null, null, agora()));
                if (encontradoId != null) {
                    encontrados.registrarResultado(encontradoId, Situacao.ANALISADO, "Nova versão do edital.",
                            documentoId, agora());
                }
                return 1;
            }
        }

        TipoDocumento tipo = RETIFICACAO.matcher(nome + " " + textoDoLink).find() ? TipoDocumento.RETIFICACAO
                : TipoDocumento.COMUNICADO;
        ServicoIngestao.DocumentoRegistrado reg = ingestao.registrarDocumentoDoConcurso(k.id(), pdf, url, nome, tipo, fonte);
        if (reg.jaExistia()) {
            if (encontradoId != null) {
                encontrados.registrarResultado(encontradoId, Situacao.IGNORADO,
                        "Mesmo arquivo de um documento já guardado (" + reg.nome() + ").", reg.documentoId(), agora());
            }
            return 0;
        }
        int n = propor(k, reg.documentoId(), nome, fonte, r, publicados);
        if (encontradoId != null) {
            encontrados.registrarResultado(encontradoId, Situacao.ANALISADO, n + " proposta(s)", reg.documentoId(), agora());
        }
        return n;
    }

    // ================================================================== propostas

    private int propor(Acompanhamento k, long documentoId, String nome, ValidadorFontes.TipoFonte fonte,
                       AnalisadorAlteracoes.Resultado r, List<CargoLinha> publicados) {
        LocalDateTime agora = agora();
        List<AlteracaoLinha> propostas = new ArrayList<>();
        propostas.add(proposta(k.id(), documentoId, Tipo.NOVO_DOCUMENTO, "Novo documento", null,
                nome + (fonte.oficial() ? "" : " (fonte não confirmada como oficial: " + fonte + ")"),
                String.valueOf(documentoId), null, null, agora));

        for (AnalisadorAlteracoes.Alteracao a : r.alteracoes()) {
            switch (a.tipo()) {
                case PRAZO_INSCRICAO -> {
                    LocalDate nova = LocalDate.parse(a.valor());
                    if (!nova.equals(k.inscricaoFim())) {
                        propostas.add(proposta(k.id(), documentoId, Tipo.PRAZO_INSCRICAO, "Fim das inscrições",
                                data(k.inscricaoFim()), DATA.format(nova), a.valor(), a.evidencia(), null, agora));
                    }
                }
                case DATA_PROVA -> {
                    LocalDate nova = LocalDate.parse(a.valor());
                    if (!nova.equals(k.dataProva())) {
                        propostas.add(proposta(k.id(), documentoId, Tipo.DATA_PROVA, "Data da prova",
                                data(k.dataProva()), DATA.format(nova), a.valor(), a.evidencia(), null, agora));
                    }
                }
                case SITUACAO -> {
                    if (!a.valor().equals(k.status())) {
                        propostas.add(proposta(k.id(), documentoId, Tipo.SITUACAO, "Situação do concurso",
                                rotuloStatus(k.status()), StatusConcurso.valueOf(a.valor()).rotulo(), a.valor(),
                                a.evidencia(), null, agora));
                    }
                }
                case TRECHO_ALTERADO -> propostas.add(proposta(k.id(), documentoId, Tipo.TRECHO_ALTERADO,
                        "Trecho alterado", limitar(a.antes(), 1000), limitar(a.valor(), 1000),
                        documentoId + "|" + limitar(a.valor(), 1900), a.evidencia(), null, agora));
                case NOVA_REDACAO -> propostas.add(proposta(k.id(), documentoId, Tipo.TRECHO_ALTERADO,
                        "Nova redação", limitar(a.antes(), 1000), limitar(a.valor(), 1000),
                        documentoId + "|" + limitar(a.valor(), 1900), a.evidencia(), null, agora));
            }
        }

        Map<String, CargoLinha> porNome = publicados.stream()
                .collect(Collectors.toMap(CargoLinha::nome, Function.identity(), (x, y) -> x));
        for (AnalisadorAlteracoes.CargoCitado citado : r.cargosCitados()) {
            CargoLinha cargo = porNome.get(citado.nome());
            if (cargo != null) {
                propostas.add(proposta(k.id(), documentoId, Tipo.CARGO_CITADO, "Cargo publicado citado", null,
                        cargo.nome() + " aparece neste documento: confira se algo mudou para esse cargo.",
                        documentoId + "|" + cargo.id(), citado.evidencia(), cargo.id(), agora));
            }
        }

        int gravadas = 0;
        for (AlteracaoLinha p : propostas) {
            // a mesma mudança citada em outro documento (ou já decidida) não volta para a fila
            if (!alteracoes.existe(k.id(), Tipo.valueOf(p.tipo()), p.valor())) {
                alteracoes.inserir(p);
                gravadas++;
            }
        }
        return gravadas;
    }

    private static AlteracaoLinha proposta(long concursoId, long documentoId, Tipo tipo, String campo, String antes,
                                           String depois, String valor, Evidencia ev, Long cargoId, LocalDateTime quando) {
        return new AlteracaoLinha(null, concursoId, documentoId, campo, antes, limitar(depois, 1000), quando, tipo.name(),
                Status.PENDENTE.name(), limitar(valor, 2000), ev == null ? null : ev.pagina(),
                ev == null ? null : limitar(ev.trecho(), 4000), cargoId, null);
    }

    /**
     * Pista vinda do agregador (ex.: o PCI passou a mostrar outro prazo). Nunca é aplicada: avisa o
     * revisor e antecipa a visita à página oficial, onde deve estar o documento que confirma.
     */
    public void registrarSinal(long concursoId, String campo, String antes, String depois, String origem) {
        String valor = campo + "|" + depois;
        if (!alteracoes.existe(concursoId, Tipo.SINAL_AGREGADOR, valor)) {
            alteracoes.inserir(new AlteracaoLinha(null, concursoId, null, campo, antes,
                    depois + " — segundo " + origem + "; o robô vai procurar o documento oficial.", agora(),
                    Tipo.SINAL_AGREGADOR.name(), Status.PENDENTE.name(), valor, null, null, null, null));
        }
        concursos.marcarParaChecar(concursoId);
    }

    // ================================================================== decisões do revisor

    /** Aplica ao concurso: novo prazo, nova data de prova, suspensão/cancelamento, ou lista o documento. */
    public void aplicar(long alteracaoId) {
        AlteracaoLinha a = pendente(alteracaoId);
        LocalDateTime agora = agora();
        switch (Tipo.valueOf(a.tipo())) {
            case PRAZO_INSCRICAO -> concursos.aplicarInscricaoFim(a.concursoId(), LocalDate.parse(a.valor()), agora);
            case DATA_PROVA -> concursos.aplicarDataProva(a.concursoId(), LocalDate.parse(a.valor()), agora);
            case SITUACAO -> concursos.aplicarStatus(a.concursoId(), a.valor(), agora);
            case NOVO_DOCUMENTO -> {
                // aplicar = o documento aparece na página pública do concurso
            }
            default -> throw new IllegalArgumentException("Esta alteração é só informativa: marque como vista.");
        }
        alteracoes.decidir(alteracaoId, Status.APLICADA, agora);
    }

    public void marcarVista(long alteracaoId) {
        pendente(alteracaoId);
        alteracoes.decidir(alteracaoId, Status.VISTA, agora());
    }

    public void descartar(long alteracaoId) {
        pendente(alteracaoId);
        alteracoes.decidir(alteracaoId, Status.DESCARTADA, agora());
    }

    /** O documento cita um cargo publicado: o cargo sai da página pública e volta para a fila de revisão. */
    public void reabrirCargoCitado(long alteracaoId) {
        AlteracaoLinha a = pendente(alteracaoId);
        if (a.cargoId() == null) {
            throw new IllegalArgumentException("Esta alteração não cita um cargo.");
        }
        revisao.reabrir(a.cargoId());
        alteracoes.decidir(alteracaoId, Status.VISTA, agora());
    }

    /** Página oficial informada pelo revisor (prevalece sobre a que o robô achou). */
    public void definirPagina(long concursoId, String url) {
        concursos.acompanhamento(concursoId)
                .orElseThrow(() -> new NoSuchElementException("Concurso " + concursoId + " não encontrado"));
        URI uri = URI.create(url.strip());
        if (uri.getScheme() == null || !uri.getScheme().startsWith("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("Informe um endereço completo, começando com https://");
        }
        concursos.definirPaginaOficial(concursoId, uri.toString(), true);
    }

    private AlteracaoLinha pendente(long id) {
        AlteracaoLinha a = alteracoes.porId(id).orElseThrow(() -> new NoSuchElementException("Alteração " + id + " não encontrada"));
        if (!Status.PENDENTE.name().equals(a.status())) {
            throw new IllegalArgumentException("Esta alteração já foi decidida.");
        }
        return a;
    }

    // ------------------------------------------------------------------ utilitários

    private static String data(LocalDate d) {
        return d == null ? "não identificada" : DATA.format(d);
    }

    private static String rotuloStatus(String status) {
        try {
            return StatusConcurso.valueOf(status).rotulo();
        } catch (IllegalArgumentException | NullPointerException e) {
            return status;
        }
    }

    private static String nomeDaUrl(String url) {
        String caminho = url.replaceAll("[?#].*$", "");
        String nome = caminho.substring(caminho.lastIndexOf('/') + 1);
        return nome.isBlank() ? url : nome;
    }

    private static String limitar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(relogio);
    }
}
