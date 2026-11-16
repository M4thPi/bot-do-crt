package br.gov.crtrj.concursos.persistencia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** PDFs vistos na página oficial de um concurso depois do edital; cada um é baixado e analisado uma vez. */
@Repository
public class DocumentoEncontradoRepositorio {

    public enum Situacao {
        /** Ainda não baixado. */
        NOVO,
        /** Baixado e analisado; as mudanças viraram propostas. */
        ANALISADO,
        /** Mesmo arquivo de um documento já guardado, ou não é PDF. */
        IGNORADO,
        /** Falha no download (tenta de novo nas próximas visitas). */
        ERRO
    }

    public record DocumentoEncontradoLinha(Long id, Long concursoId, String url, String texto, String situacao,
                                           String motivo, Long documentoId, int tentativas, LocalDateTime encontradoEm,
                                           LocalDateTime atualizadoEm) {
    }

    private final JdbcClient jdbc;

    public DocumentoEncontradoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true se o documento ainda não era conhecido */
    public boolean registrarSeNovo(long concursoId, String url, String texto, LocalDateTime quando) {
        long existentes = jdbc.sql("select count(*) from documento_encontrado where concurso_id = :c and url = :u")
                .param("c", concursoId).param("u", url).query(Long.class).single();
        if (existentes > 0) {
            return false;
        }
        jdbc.sql("""
                        insert into documento_encontrado (concurso_id, url, texto, situacao, tentativas, encontrado_em,
                            atualizado_em)
                        values (:c, :u, :t, 'NOVO', 0, :q, :q)
                        """)
                .param("c", concursoId).param("u", limitar(url, 2000)).param("t", limitar(texto, 700)).param("q", quando)
                .update();
        return true;
    }

    /** A baixar: novos e os que falharam menos de {@code maxTentativas} vezes. */
    public List<DocumentoEncontradoLinha> aProcessar(long concursoId, int maxTentativas, int limite) {
        return jdbc.sql("""
                        select * from documento_encontrado
                        where concurso_id = :c and (situacao = 'NOVO' or (situacao = 'ERRO' and tentativas < :max))
                        order by encontrado_em, id
                        limit :lim
                        """)
                .param("c", concursoId).param("max", maxTentativas).param("lim", limite)
                .query(DocumentoEncontradoLinha.class).list();
    }

    public List<DocumentoEncontradoLinha> listarPorConcurso(long concursoId) {
        return jdbc.sql("select * from documento_encontrado where concurso_id = :c order by encontrado_em desc, id desc")
                .param("c", concursoId).query(DocumentoEncontradoLinha.class).list();
    }

    public void registrarResultado(long id, Situacao situacao, String motivo, Long documentoId, LocalDateTime quando) {
        jdbc.sql("""
                        update documento_encontrado set situacao = :s, motivo = :m, documento_id = :d,
                            tentativas = tentativas + 1, atualizado_em = :q
                        where id = :id
                        """)
                .param("s", situacao.name()).param("m", limitar(motivo, 1000)).param("d", documentoId).param("q", quando)
                .param("id", id).update();
    }

    private static String limitar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
