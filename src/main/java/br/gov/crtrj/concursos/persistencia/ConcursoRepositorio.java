package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class ConcursoRepositorio {

    public record ConcursoLinha(Long id, String chaveDedup, String nome, String orgao, String numeroEdital, String banca,
                                String esfera, String uf, String municipio, String status, LocalDate inscricaoInicio,
                                LocalDate inscricaoFim, LocalDate dataProva, String siteInscricao,
                                LocalDateTime encontradoEm, LocalDateTime ultimaVerificacao, LocalDateTime atualizadoEm) {
    }

    private final JdbcClient jdbc;

    public ConcursoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ConcursoLinha> porChave(String chave) {
        return jdbc.sql("select * from concurso where chave_dedup = :chave").param("chave", chave)
                .query(ConcursoLinha.class).optional();
    }

    public Optional<ConcursoLinha> porId(long id) {
        return jdbc.sql("select * from concurso where id = :id").param("id", id).query(ConcursoLinha.class).optional();
    }

    public List<ConcursoLinha> listar(int pagina, int tamanho) {
        return jdbc.sql("select * from concurso order by inscricao_fim desc nulls last, id desc limit :lim offset :off")
                .param("lim", tamanho).param("off", pagina * tamanho)
                .query(ConcursoLinha.class).list();
    }

    public long contar() {
        return jdbc.sql("select count(*) from concurso").query(Long.class).single();
    }

    public long inserir(ConcursoLinha c) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into concurso (chave_dedup, nome, orgao, numero_edital, banca, esfera, uf, municipio, status,
                            inscricao_inicio, inscricao_fim, data_prova, site_inscricao, encontrado_em, ultima_verificacao, atualizado_em)
                        values (:chaveDedup, :nome, :orgao, :numeroEdital, :banca, :esfera, :uf, :municipio, :status,
                            :inscricaoInicio, :inscricaoFim, :dataProva, :siteInscricao, :encontradoEm, :ultimaVerificacao, :atualizadoEm)
                        """)
                .paramSource(c).update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }

    public void atualizar(ConcursoLinha c) {
        jdbc.sql("""
                        update concurso set nome = :nome, orgao = :orgao, numero_edital = :numeroEdital, banca = :banca,
                            esfera = :esfera, uf = :uf, municipio = :municipio, status = :status,
                            inscricao_inicio = :inscricaoInicio, inscricao_fim = :inscricaoFim, data_prova = :dataProva,
                            site_inscricao = :siteInscricao, ultima_verificacao = :ultimaVerificacao, atualizado_em = :atualizadoEm
                        where id = :id
                        """)
                .paramSource(c).update();
    }

    public void registrarVerificacao(long id, LocalDateTime quando) {
        jdbc.sql("update concurso set ultima_verificacao = :q where id = :id").param("q", quando).param("id", id).update();
    }

    // ---------------------------------------------------------------- acompanhamento

    /**
     * @param paginaOficial página onde o edital foi encontrado; é nela que as retificações aparecem
     * @param checarAgora   algum sinal (ex.: o PCI mudou o prazo) pede uma visita antes do intervalo normal
     */
    public record Acompanhamento(long id, String nome, String orgao, String numeroEdital, String paginaOficial,
                                 LocalDateTime acompanhadoEm, boolean checarAgora, LocalDate inscricaoFim,
                                 LocalDate dataProva, String status) {
    }

    private static final String SELECT_ACOMPANHAMENTO = "select id, nome, orgao, numero_edital, pagina_oficial,"
            + " acompanhado_em, checar_agora, inscricao_fim, data_prova, status from concurso";

    /**
     * Concursos em andamento com visita vencida: até a prova (ou, sem data de prova, até
     * {@code limiteInscricao} depois do fim das inscrições). Suspenso continua sendo acompanhado.
     */
    public List<Acompanhamento> aAcompanhar(LocalDateTime corte, LocalDate hoje, LocalDate limiteInscricao, int limite) {
        return jdbc.sql(SELECT_ACOMPANHAMENTO + """
                         where pagina_oficial is not null
                          and status not in ('CANCELADO', 'HOMOLOGADO', 'RESULTADO_DIVULGADO')
                          and (checar_agora or acompanhado_em is null or acompanhado_em < :corte)
                          and (data_prova >= :hoje
                               or (data_prova is null and (inscricao_fim is null or inscricao_fim >= :limiteInscricao)))
                        order by checar_agora desc, acompanhado_em nulls first, id
                        limit :lim
                        """)
                .param("corte", corte).param("hoje", hoje).param("limiteInscricao", limiteInscricao).param("lim", limite)
                .query(Acompanhamento.class).list();
    }

    public List<Acompanhamento> listarAcompanhamento() {
        return jdbc.sql(SELECT_ACOMPANHAMENTO + " order by inscricao_fim desc nulls last, id desc")
                .query(Acompanhamento.class).list();
    }

    public Optional<Acompanhamento> acompanhamento(long id) {
        return jdbc.sql(SELECT_ACOMPANHAMENTO + " where id = :id").param("id", id).query(Acompanhamento.class).optional();
    }

    /** @param sobrescrever false = só grava se ainda não houver página (a informada pelo revisor prevalece) */
    public void definirPaginaOficial(long id, String url, boolean sobrescrever) {
        jdbc.sql("update concurso set pagina_oficial = :u, checar_agora = true where id = :id"
                        + (sobrescrever ? "" : " and pagina_oficial is null"))
                .param("u", url).param("id", id).update();
    }

    public void marcarParaChecar(long id) {
        jdbc.sql("update concurso set checar_agora = true where id = :id").param("id", id).update();
    }

    public void registrarAcompanhamento(long id, LocalDateTime quando) {
        jdbc.sql("update concurso set acompanhado_em = :q, checar_agora = false where id = :id")
                .param("q", quando).param("id", id).update();
    }

    public void aplicarInscricaoFim(long id, LocalDate data, LocalDateTime quando) {
        jdbc.sql("update concurso set inscricao_fim = :d, atualizado_em = :q where id = :id")
                .param("d", data).param("q", quando).param("id", id).update();
    }

    public void aplicarDataProva(long id, LocalDate data, LocalDateTime quando) {
        jdbc.sql("update concurso set data_prova = :d, atualizado_em = :q where id = :id")
                .param("d", data).param("q", quando).param("id", id).update();
    }

    public void aplicarStatus(long id, String status, LocalDateTime quando) {
        jdbc.sql("update concurso set status = :s, atualizado_em = :q where id = :id")
                .param("s", status).param("q", quando).param("id", id).update();
    }
}
