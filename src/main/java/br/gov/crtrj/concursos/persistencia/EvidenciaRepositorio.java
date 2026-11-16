package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Evidências campo a campo: de onde veio cada dado (documento, página, item, trecho). */
@Repository
public class EvidenciaRepositorio {

    public record EvidenciaLinha(Long id, Long documentoId, Long cargoId, String campo, String valor, String status,
                                 int pagina, String secao, String item, String trecho, String metodo, double score,
                                 LocalDateTime criadaEm) {
    }

    private final JdbcClient jdbc;

    public EvidenciaRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void inserir(EvidenciaLinha e) {
        jdbc.sql("""
                        insert into evidencia (documento_id, cargo_id, campo, valor, status, pagina, secao, item, trecho, metodo,
                            score, criada_em)
                        values (:documentoId, :cargoId, :campo, :valor, :status, :pagina, :secao, :item, :trecho, :metodo,
                            :score, :criadaEm)
                        """)
                .paramSource(e).update();
    }

    public List<EvidenciaLinha> listarPorCargo(long cargoId) {
        return jdbc.sql("select * from evidencia where cargo_id = :c order by id").param("c", cargoId)
                .query(EvidenciaLinha.class).list();
    }

    public List<EvidenciaLinha> listarDoConcurso(long documentoId) {
        return jdbc.sql("select * from evidencia where documento_id = :d and cargo_id is null order by id")
                .param("d", documentoId).query(EvidenciaLinha.class).list();
    }
}
