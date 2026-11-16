package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Histórico das execuções do robô de coleta. */
@Repository
public class ExecucaoColetaRepositorio {

    public record ExecucaoLinha(Long id, LocalDateTime iniciadaEm, LocalDateTime terminadaEm, int pistasLidas,
                                int pistasNovas, int editaisAnalisados, int erros, String resumo) {
    }

    private final JdbcClient jdbc;

    public ExecucaoColetaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long iniciar(LocalDateTime quando) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into execucao_coleta (iniciada_em, pistas_lidas, pistas_novas, editais_analisados, erros)
                        values (:q, 0, 0, 0, 0)
                        """)
                .param("q", quando).update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }

    public void terminar(long id, LocalDateTime quando, int lidas, int novas, int analisados, int erros, String resumo) {
        jdbc.sql("""
                        update execucao_coleta set terminada_em = :q, pistas_lidas = :l, pistas_novas = :n,
                            editais_analisados = :a, erros = :e, resumo = :r
                        where id = :id
                        """)
                .param("q", quando).param("l", lidas).param("n", novas).param("a", analisados).param("e", erros)
                .param("r", resumo == null || resumo.length() <= 4000 ? resumo : resumo.substring(0, 4000))
                .param("id", id).update();
    }

    public List<ExecucaoLinha> ultimas(int limite) {
        return jdbc.sql("select * from execucao_coleta order by id desc limit :lim").param("lim", limite)
                .query(ExecucaoLinha.class).list();
    }
}
