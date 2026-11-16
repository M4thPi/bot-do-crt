package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.config.ConcursosProperties;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.AlteracaoLinha;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.Tipo;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.Acompanhamento;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio.DocumentoLinha;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Retificações, prorrogações e comunicados achados depois do edital, à espera da decisão do revisor. */
@Service
public class ServicoAlteracoes {

    /**
     * @param acao APLICAR (muda o concurso ou lista o documento), VER (só informativa) ou CARGO (pode reabrir o cargo)
     * @param link documento que o robô não pôde baixar, para o revisor abrir no navegador
     */
    public record ItemAlteracao(long id, String tipo, String rotulo, String campo, String antes, String depois,
                                String trecho, Integer pagina, Long documentoId, String documentoNome,
                                LocalDateTime detectadaEm, String acao, String link) {
    }

    public record GrupoAlteracoes(long concursoId, String concurso, List<ItemAlteracao> itens) {
    }

    /**
     * @param emAcompanhamento o robô ainda visita a página (concurso em andamento, com página conhecida)
     * @param situacao         "Suspenso" ou "Cancelado"; null nos demais casos
     */
    public record ConcursoAcompanhado(long id, String nome, String paginaOficial, LocalDateTime visitadoEm,
                                      LocalDate inscricaoFim, LocalDate dataProva, String situacao,
                                      boolean emAcompanhamento, int documentos) {
    }

    /**
     * @param pendentes total de itens à espera de decisão
     * @param intervalo tempo entre duas visitas do robô à página oficial de um concurso
     */
    public record AlteracoesPendentes(List<GrupoAlteracoes> grupos, long pendentes, List<ConcursoAcompanhado> concursos,
                                      String intervalo) {
    }

    private final AlteracaoRepositorio alteracoes;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final DocumentoEncontradoRepositorio encontrados;
    private final ConcursosProperties.Acompanhamento config;
    private final Clock relogio;

    public ServicoAlteracoes(AlteracaoRepositorio alteracoes, ConcursoRepositorio concursos,
                             DocumentoRepositorio documentos, DocumentoEncontradoRepositorio encontrados,
                             ConcursosProperties props, Clock relogio) {
        this.alteracoes = alteracoes;
        this.concursos = concursos;
        this.documentos = documentos;
        this.encontrados = encontrados;
        this.config = props.acompanhamento();
        this.relogio = relogio;
    }

    public AlteracoesPendentes pendentes() {
        Map<Long, GrupoAlteracoes> grupos = new LinkedHashMap<>();
        Map<Long, Optional<DocumentoLinha>> docs = new LinkedHashMap<>();
        for (AlteracaoLinha a : alteracoes.pendentes()) {
            GrupoAlteracoes g = grupos.computeIfAbsent(a.concursoId(), id -> new GrupoAlteracoes(id,
                    concursos.porId(id).map(ConcursoRepositorio.ConcursoLinha::nome).orElse("Concurso " + id),
                    new ArrayList<>()));
            Optional<DocumentoLinha> d = a.documentoId() == null ? Optional.empty()
                    : docs.computeIfAbsent(a.documentoId(), documentos::porId);
            g.itens().add(new ItemAlteracao(a.id(), a.tipo(), rotulo(a), a.campo(), a.antes(), a.depois(),
                    a.trecho(), a.pagina(), a.documentoId(), d.map(DocumentoLinha::nome).orElse(null),
                    a.detectadaEm(), acao(a),
                    Tipo.DOCUMENTO_BLOQUEADO.name().equals(a.tipo()) ? a.valor() : null));
        }

        LocalDate hoje = LocalDate.now(relogio);
        List<ConcursoAcompanhado> lista = concursos.listarAcompanhamento().stream()
                .map(k -> new ConcursoAcompanhado(k.id(), k.nome(), k.paginaOficial(), k.acompanhadoEm(),
                        k.inscricaoFim(), k.dataProva(), situacao(k), emAcompanhamento(k, hoje),
                        encontrados.listarPorConcurso(k.id()).size()))
                .toList();
        return new AlteracoesPendentes(List.copyOf(grupos.values()), alteracoes.contarPendentes(), lista,
                config.intervalo().toHours() + " horas");
    }

    private boolean emAcompanhamento(Acompanhamento k, LocalDate hoje) {
        if (k.paginaOficial() == null || List.of("CANCELADO", "HOMOLOGADO", "RESULTADO_DIVULGADO").contains(k.status())) {
            return false;
        }
        if (k.dataProva() != null) {
            return !k.dataProva().isBefore(hoje.minusDays(1));
        }
        return k.inscricaoFim() == null || !k.inscricaoFim().isBefore(hoje.minusDays(config.diasAposInscricaoSemProva()));
    }

    private static String situacao(Acompanhamento k) {
        try {
            StatusConcurso s = StatusConcurso.valueOf(k.status());
            return s == StatusConcurso.SUSPENSO || s == StatusConcurso.CANCELADO ? s.rotulo() : null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private static String acao(AlteracaoLinha a) {
        return switch (Tipo.valueOf(a.tipo())) {
            case PRAZO_INSCRICAO, DATA_PROVA, SITUACAO, NOVO_DOCUMENTO -> "APLICAR";
            case CARGO_CITADO -> "CARGO";
            case TRECHO_ALTERADO, SINAL_AGREGADOR, DOCUMENTO_BLOQUEADO -> "VER";
        };
    }

    private static String rotulo(AlteracaoLinha a) {
        if ("Nova redação".equals(a.campo())) {
            return "Nova redação";
        }
        return switch (Tipo.valueOf(a.tipo())) {
            case NOVO_DOCUMENTO -> "Documento novo";
            case PRAZO_INSCRICAO -> "Prazo de inscrição";
            case DATA_PROVA -> "Data da prova";
            case SITUACAO -> "Situação";
            case TRECHO_ALTERADO -> "Onde se lê / leia-se";
            case CARGO_CITADO -> "Cargo publicado citado";
            case SINAL_AGREGADOR -> "Sinal do agregador";
            case DOCUMENTO_BLOQUEADO -> "Documento para enviar";
        };
    }
}
