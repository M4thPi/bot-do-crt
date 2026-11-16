package br.gov.crtrj.concursos.persistencia;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class DocumentoRepositorio {

    /**
     * Metadados do documento — o JSON da análise é lido à parte, sob demanda.
     * {@code arquivoPath} é caminho do servidor: não vai para as respostas da API (o PDF sai por /documentos/{id}/arquivo).
     */
    public record DocumentoLinha(Long id, Long concursoId, String tipo, String nome, String url, String tipoFonte,
                                 boolean oficial, String sha256, @JsonIgnore String arquivoPath, int paginas, int paginasSemTexto,
                                 String versaoExtrator, LocalDateTime baixadoEm, LocalDateTime analisadoEm) {
    }

    private static final String COLUNAS = "id, concurso_id, tipo, nome, url, tipo_fonte, oficial, sha256, arquivo_path,"
            + " paginas, paginas_sem_texto, versao_extrator, baixado_em, analisado_em";

    private final JdbcClient jdbc;

    public DocumentoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocumentoLinha> porSha256(String sha256) {
        return jdbc.sql("select " + COLUNAS + " from documento where sha256 = :s").param("s", sha256)
                .query(DocumentoLinha.class).optional();
    }

    public Optional<DocumentoLinha> porId(long id) {
        return jdbc.sql("select " + COLUNAS + " from documento where id = :id").param("id", id)
                .query(DocumentoLinha.class).optional();
    }

    public List<DocumentoLinha> listarPorConcurso(long concursoId) {
        return jdbc.sql("select " + COLUNAS + " from documento where concurso_id = :c order by analisado_em")
                .param("c", concursoId).query(DocumentoLinha.class).list();
    }

    public Optional<String> resultadoJson(long id) {
        return jdbc.sql("select resultado_json from documento where id = :id").param("id", id).query(String.class).optional();
    }

    public long inserir(DocumentoLinha d, String resultadoJson) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into documento (concurso_id, tipo, nome, url, tipo_fonte, oficial, sha256, arquivo_path, paginas,
                            paginas_sem_texto, versao_extrator, resultado_json, baixado_em, analisado_em)
                        values (:concursoId, :tipo, :nome, :url, :tipoFonte, :oficial, :sha256, :arquivoPath, :paginas,
                            :paginasSemTexto, :versaoExtrator, :json, :baixadoEm, :analisadoEm)
                        """)
                .param("concursoId", d.concursoId())
                .param("tipo", d.tipo())
                .param("nome", d.nome())
                .param("url", d.url())
                .param("tipoFonte", d.tipoFonte())
                .param("oficial", d.oficial())
                .param("sha256", d.sha256())
                .param("arquivoPath", d.arquivoPath())
                .param("paginas", d.paginas())
                .param("paginasSemTexto", d.paginasSemTexto())
                .param("versaoExtrator", d.versaoExtrator())
                .param("json", resultadoJson)
                .param("baixadoEm", d.baixadoEm())
                .param("analisadoEm", d.analisadoEm())
                .update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }
}
