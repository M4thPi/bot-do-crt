package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pistas de concursos vindas das fontes de descoberta e o que aconteceu com cada uma. */
@Repository
public class PistaRepositorio {

    /** Situação da pista no funil de coleta. */
    public enum StatusPista {
        /** Ainda não processada. */
        NOVA,
        /** A listagem mostra que não há nível técnico. */
        DESCARTADA,
        /** A notícia não cita site oficial. */
        SEM_FONTE_OFICIAL,
        /** Site oficial encontrado, mas nenhum PDF parece o edital de abertura. */
        EDITAL_NAO_ENCONTRADO,
        /** Site fora do ar, bloqueio, certificado inválido... */
        ERRO_ACESSO,
        /** Edital oficial baixado e analisado. */
        ANALISADA,
        /** O site não permite robôs (robots.txt): o revisor baixa pelo navegador e envia. Não é tentada de novo. */
        BLOQUEADA
    }

    public record PistaLinha(Long id, String fonte, String url, String titulo, String orgao, String uf, String vagasTexto,
                             String cargosTexto, String escolaridade, String inscricaoAte, String status, String motivo,
                             String urlOficial, Long documentoId, Long concursoId, int tentativas,
                             LocalDateTime encontradaEm, LocalDateTime atualizadaEm, String texto) {
    }

    private final JdbcClient jdbc;

    public PistaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PistaLinha> porUrl(String url) {
        return jdbc.sql("select * from pista where url = :u").param("u", url).query(PistaLinha.class).optional();
    }

    public Optional<PistaLinha> porId(long id) {
        return jdbc.sql("select * from pista where id = :id").param("id", id).query(PistaLinha.class).optional();
    }

    public long inserir(PistaLinha p) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into pista (fonte, url, titulo, orgao, uf, vagas_texto, cargos_texto, escolaridade, inscricao_ate,
                            status, motivo, url_oficial, documento_id, concurso_id, tentativas, encontrada_em, atualizada_em, texto)
                        values (:fonte, :url, :titulo, :orgao, :uf, :vagasTexto, :cargosTexto, :escolaridade, :inscricaoAte,
                            :status, :motivo, :urlOficial, :documentoId, :concursoId, :tentativas, :encontradaEm, :atualizadaEm, :texto)
                        """)
                .paramSource(p).update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }

    /** Atualiza o que a listagem mostra hoje (prazo prorrogado, vagas...) sem mexer no status. */
    public void atualizarListagem(long id, String titulo, String vagas, String cargos, String escolaridade, String inscricaoAte) {
        jdbc.sql("""
                        update pista set titulo = :t, vagas_texto = :v, cargos_texto = :c, escolaridade = :e, inscricao_ate = :i
                        where id = :id
                        """)
                .param("t", titulo).param("v", vagas).param("c", cargos).param("e", escolaridade).param("i", inscricaoAte)
                .param("id", id).update();
    }

    public void registrarResultado(long id, StatusPista status, String motivo, String urlOficial, Long documentoId,
                                   Long concursoId, LocalDateTime quando) {
        jdbc.sql("""
                        update pista set status = :s, motivo = :m, url_oficial = :u, documento_id = :d, concurso_id = :c,
                            tentativas = tentativas + 1, atualizada_em = :q
                        where id = :id
                        """)
                .param("s", status.name()).param("m", limitar(motivo, 1000)).param("u", limitar(urlOficial, 2000))
                .param("d", documentoId).param("c", concursoId).param("q", quando).param("id", id).update();
    }

    /**
     * Pistas a processar: novas, e as que falharam há mais de {@code horasEntreTentativas} horas
     * e ainda não esgotaram as tentativas.
     */
    public List<PistaLinha> pendentes(LocalDateTime agora, int horasEntreTentativas, int maxTentativas, int limite) {
        return jdbc.sql("""
                        select * from pista
                        where status = 'NOVA'
                           or (status in ('SEM_FONTE_OFICIAL', 'EDITAL_NAO_ENCONTRADO', 'ERRO_ACESSO')
                               and tentativas < :max and atualizada_em < :corte)
                        order by encontrada_em, id
                        limit :lim
                        """)
                .param("max", maxTentativas).param("corte", agora.minusHours(horasEntreTentativas)).param("lim", limite)
                .query(PistaLinha.class).list();
    }

    public List<PistaLinha> listar(String status, int pagina, int tamanho) {
        String filtro = status == null ? "" : " where status = :s";
        var sql = jdbc.sql("select * from pista" + filtro + " order by atualizada_em desc, id desc limit :lim offset :off")
                .param("lim", tamanho).param("off", pagina * tamanho);
        if (status != null) {
            sql = sql.param("s", status);
        }
        return sql.query(PistaLinha.class).list();
    }

    /** Pistas em que o robô parou e o revisor pode ajudar (informando o edital ou descartando). */
    public List<PistaLinha> aguardandoRevisor() {
        return jdbc.sql("""
                        select * from pista
                        where status in ('SEM_FONTE_OFICIAL', 'EDITAL_NAO_ENCONTRADO', 'ERRO_ACESSO', 'BLOQUEADA')
                        order by atualizada_em desc, id desc
                        """)
                .query(PistaLinha.class).list();
    }

    public Map<String, Long> contarPorStatus() {
        Map<String, Long> contagem = new HashMap<>();
        jdbc.sql("select status, count(*) as total from pista group by status")
                .query((rs, n) -> contagem.put(rs.getString("status"), rs.getLong("total")))
                .list();
        return contagem;
    }

    public void descartar(long id, String motivo, LocalDateTime quando) {
        jdbc.sql("update pista set status = 'DESCARTADA', motivo = :m, atualizada_em = :q where id = :id")
                .param("m", motivo).param("q", quando).param("id", id).update();
    }

    private static String limitar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
