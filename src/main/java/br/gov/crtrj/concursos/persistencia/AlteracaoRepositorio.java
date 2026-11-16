package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mudanças num concurso: diferenças entre versões do edital (histórico) e propostas vindas de
 * retificações e comunicados, que o revisor aplica ou descarta.
 */
@Repository
public class AlteracaoRepositorio {

    /** Estado de uma alteração. */
    public enum Status {
        /** Aguarda o revisor. */
        PENDENTE,
        /** Aplicada ao concurso (ou documento listado na página pública). */
        APLICADA,
        /** O revisor achou que não procede. */
        DESCARTADA,
        /** Informação lida pelo revisor (trecho alterado, sinal do agregador...). */
        VISTA,
        /** Histórico: diferença entre versões do edital, já refletida nos dados. */
        REGISTRADA
    }

    /** O que a alteração propõe. */
    public enum Tipo {
        /** Retificação/comunicado novo; aplicar = listar o documento na página pública. */
        NOVO_DOCUMENTO,
        PRAZO_INSCRICAO,
        DATA_PROVA,
        /** Suspensão ou cancelamento do concurso. */
        SITUACAO,
        /** Par "onde se lê / leia-se". */
        TRECHO_ALTERADO,
        /** O documento novo cita um cargo que está publicado. */
        CARGO_CITADO,
        /** Pista vinda de agregador (ex.: o PCI mudou o prazo); nunca é aplicada sozinha. */
        SINAL_AGREGADOR,
        /** Documento novo na página oficial que o robô não pode baixar (robots.txt): o revisor envia o arquivo. */
        DOCUMENTO_BLOQUEADO
    }

    /**
     * @param campo   rótulo curto ("Prazo de inscrição")
     * @param antes   valor atual ou texto substituído
     * @param depois  descrição legível do novo valor
     * @param tipo    null = diferença entre versões do edital
     * @param valor   valor máquina (data ISO, SUSPENSO...) ou o novo texto
     * @param cargoId cargo citado (CARGO_CITADO)
     */
    public record AlteracaoLinha(Long id, Long concursoId, Long documentoId, String campo, String antes, String depois,
                                 LocalDateTime detectadaEm, String tipo, String status, String valor, Integer pagina,
                                 String trecho, Long cargoId, LocalDateTime decididaEm) {

        /** Diferença entre versões do edital, só para histórico. */
        public static AlteracaoLinha registro(long concursoId, Long documentoId, String campo, String antes, String depois,
                                              LocalDateTime quando) {
            return new AlteracaoLinha(null, concursoId, documentoId, campo, antes, depois, quando, null,
                    Status.REGISTRADA.name(), null, null, null, null, null);
        }
    }

    private final JdbcClient jdbc;

    public AlteracaoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long inserir(AlteracaoLinha a) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into alteracao (concurso_id, documento_id, campo, antes, depois, detectada_em, tipo, status,
                            valor, pagina, trecho, cargo_id, decidida_em)
                        values (:concursoId, :documentoId, :campo, :antes, :depois, :detectadaEm, :tipo, :status,
                            :valor, :pagina, :trecho, :cargoId, :decididaEm)
                        """)
                .paramSource(a).update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }

    public Optional<AlteracaoLinha> porId(long id) {
        return jdbc.sql("select * from alteracao where id = :id").param("id", id).query(AlteracaoLinha.class).optional();
    }

    public List<AlteracaoLinha> listarPorConcurso(long concursoId) {
        return jdbc.sql("select * from alteracao where concurso_id = :c order by detectada_em, id")
                .param("c", concursoId).query(AlteracaoLinha.class).list();
    }

    public List<AlteracaoLinha> pendentes() {
        return jdbc.sql("select * from alteracao where status = 'PENDENTE' order by concurso_id, detectada_em, id")
                .query(AlteracaoLinha.class).list();
    }

    public long contarPendentes() {
        return jdbc.sql("select count(*) from alteracao where status = 'PENDENTE'").query(Long.class).single();
    }

    /** Já existe proposta igual (pendente ou decidida)? Evita repetir a mesma mudança citada em dois documentos. */
    public boolean existe(long concursoId, Tipo tipo, String valor) {
        return jdbc.sql("select count(*) from alteracao where concurso_id = :c and tipo = :t and valor = :v")
                .param("c", concursoId).param("t", tipo.name()).param("v", valor).query(Long.class).single() > 0;
    }

    public List<AlteracaoLinha> doTipo(long concursoId, Tipo tipo, Status status) {
        return jdbc.sql("select * from alteracao where concurso_id = :c and tipo = :t and status = :s order by decidida_em, id")
                .param("c", concursoId).param("t", tipo.name()).param("s", status.name())
                .query(AlteracaoLinha.class).list();
    }

    public int decidir(long id, Status status, LocalDateTime quando) {
        return jdbc.sql("update alteracao set status = :s, decidida_em = :q where id = :id and status = 'PENDENTE'")
                .param("s", status.name()).param("q", quando).param("id", id).update();
    }
}
