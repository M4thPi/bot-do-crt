package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Edições de diário oficial já lidas pelo robô (uma por dia e seção). */
@Repository
public class EdicaoDiarioRepositorio {

    public enum Situacao {
        LIDA,
        /** Dia sem edição (fim de semana, feriado). */
        SEM_EDICAO
    }

    public record EdicaoLinha(String fonte, LocalDate data, String secao, String situacao, int atosLidos, int pistas,
                              LocalDateTime processadaEm) {
    }

    private final JdbcClient jdbc;

    public EdicaoDiarioRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lida(String fonte, LocalDate data, String secao) {
        return jdbc.sql("select count(*) from edicao_diario where fonte = :f and data = :d and secao = :s")
                .param("f", fonte).param("d", data).param("s", secao).query(Long.class).single() > 0;
    }

    public void registrar(String fonte, LocalDate data, String secao, Situacao situacao, int atosLidos, int pistas,
                          LocalDateTime quando) {
        int alteradas = jdbc.sql("""
                        update edicao_diario set situacao = :situacao, atos_lidos = :atos, pistas = :pistas, processada_em = :q
                        where fonte = :f and data = :d and secao = :s
                        """)
                .param("f", fonte).param("d", data).param("s", secao).param("situacao", situacao.name())
                .param("atos", atosLidos).param("pistas", pistas).param("q", quando).update();
        if (alteradas == 0) {
            jdbc.sql("""
                            insert into edicao_diario (fonte, data, secao, situacao, atos_lidos, pistas, processada_em)
                            values (:f, :d, :s, :situacao, :atos, :pistas, :q)
                            """)
                    .param("f", fonte).param("d", data).param("s", secao).param("situacao", situacao.name())
                    .param("atos", atosLidos).param("pistas", pistas).param("q", quando).update();
        }
    }

    public List<EdicaoLinha> ultimas(String fonte, int limite) {
        return jdbc.sql("select * from edicao_diario where fonte = :f order by data desc, secao limit :lim")
                .param("f", fonte).param("lim", limite).query(EdicaoLinha.class).list();
    }
}
