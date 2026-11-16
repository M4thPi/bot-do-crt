package br.gov.crtrj.concursos.persistencia;

import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
public class CargoRepositorio {

    public record CargoLinha(Long id, Long concursoId, Long documentoId, int indice, String chave, String nome,
                             String especialidade, String escolaridade, String formacoes, String formacoesNomes,
                             String conselhoExigido, String conselhoTipo, String conselhoLiteral, String exigeCrtRj,
                             String registroAtivo, String momentoRegistro, boolean exigenciaGenerica,
                             Integer vagasTotal, Integer vagasImediatas, Integer vagasAmpla, Integer vagasPcd,
                             boolean cadastroReserva, BigDecimal salarioBase, BigDecimal remuneracaoTotal,
                             BigDecimal valorQuadro, Integer cargaHoraria, BigDecimal taxaInscricao,
                             String classificacao, String motivo, String revisaoStatus, String revisaoObs,
                             LocalDateTime revisadoEm, String classificacaoPublicada, String justificativaRevisor,
                             String camposOcultos) {

        /** Esta linha com a decisão do revisor registrada em {@code outra}. */
        public CargoLinha comRevisaoDe(CargoLinha outra) {
            return new CargoLinha(id, concursoId, documentoId, indice, chave, nome, especialidade, escolaridade, formacoes,
                    formacoesNomes, conselhoExigido, conselhoTipo, conselhoLiteral, exigeCrtRj, registroAtivo,
                    momentoRegistro, exigenciaGenerica, vagasTotal, vagasImediatas, vagasAmpla, vagasPcd, cadastroReserva,
                    salarioBase, remuneracaoTotal, valorQuadro, cargaHoraria, taxaInscricao, classificacao, motivo,
                    outra.revisaoStatus(), outra.revisaoObs(), outra.revisadoEm(), outra.classificacaoPublicada(),
                    outra.justificativaRevisor(), outra.camposOcultos());
        }
    }

    /**
     * Filtros do item 11 da especificação. Campos nulos não filtram.
     *
     * @param formacaoId               id do catálogo (resolvido a partir do texto informado)
     * @param aceitaCrt                só editais que citam CFT/CRT literalmente
     * @param incluirConselhoGenerico  com aceitaCrt, inclui "conselho competente" (sem presumir que é CRT)
     */
    public record Filtro(String uf, String municipio, String formacaoId, String especialidade, BigDecimal salarioMin,
                         BigDecimal salarioMax, String conselho, Boolean aceitaCrt, Boolean incluirConselhoGenerico,
                         Boolean semConselho, StatusConcurso status, Boolean cadastroReserva, Boolean vagasImediatas,
                         String banca, String orgao, String esfera, LocalDate inscricaoAte,
                         List<Classificacao> classificacoes, LocalDate hoje) {
    }

    private final JdbcClient jdbc;

    public CargoRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CargoLinha> porId(long id) {
        return jdbc.sql("select * from cargo where id = :id").param("id", id).query(CargoLinha.class).optional();
    }

    public List<CargoLinha> listarPorConcurso(long concursoId) {
        return jdbc.sql("select * from cargo where concurso_id = :c order by classificacao, nome")
                .param("c", concursoId).query(CargoLinha.class).list();
    }

    public void excluirPorConcurso(long concursoId) {
        jdbc.sql("delete from evidencia where cargo_id in (select id from cargo where concurso_id = :c)").param("c", concursoId).update();
        jdbc.sql("delete from cargo where concurso_id = :c").param("c", concursoId).update();
    }

    public long inserir(CargoLinha c) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.sql("""
                        insert into cargo (concurso_id, documento_id, indice, chave, nome, especialidade, escolaridade, formacoes,
                            formacoes_nomes, conselho_exigido, conselho_tipo, conselho_literal, exige_crt_rj, registro_ativo,
                            momento_registro, exigencia_generica, vagas_total, vagas_imediatas, vagas_ampla, vagas_pcd,
                            cadastro_reserva, salario_base, remuneracao_total, valor_quadro, carga_horaria, taxa_inscricao,
                            classificacao, motivo, revisao_status, revisao_obs, revisado_em, classificacao_publicada,
                            justificativa_revisor, campos_ocultos)
                        values (:concursoId, :documentoId, :indice, :chave, :nome, :especialidade, :escolaridade, :formacoes,
                            :formacoesNomes, :conselhoExigido, :conselhoTipo, :conselhoLiteral, :exigeCrtRj, :registroAtivo,
                            :momentoRegistro, :exigenciaGenerica, :vagasTotal, :vagasImediatas, :vagasAmpla, :vagasPcd,
                            :cadastroReserva, :salarioBase, :remuneracaoTotal, :valorQuadro, :cargaHoraria, :taxaInscricao,
                            :classificacao, :motivo, :revisaoStatus, :revisaoObs, :revisadoEm, :classificacaoPublicada,
                            :justificativaRevisor, :camposOcultos)
                        """)
                .paramSource(c).update(chave, "id");
        return Objects.requireNonNull(chave.getKey()).longValue();
    }

    /**
     * @param classificacaoPublicada null quando o cargo não vai para a página pública
     * @param justificativaRevisor   justificativa pública de uma classificação corrigida na revisão
     * @param camposOcultos          dados que o revisor mandou não publicar (ver {@code CampoPublico})
     */
    public int registrarRevisao(long id, String status, String classificacaoPublicada, String justificativaRevisor,
                                String camposOcultos, String observacao, LocalDateTime quando) {
        return jdbc.sql("""
                        update cargo set revisao_status = :s, classificacao_publicada = :cp, justificativa_revisor = :j,
                            campos_ocultos = :co, revisao_obs = :o, revisado_em = :q
                        where id = :id
                        """)
                .param("s", status).param("cp", classificacaoPublicada).param("j", justificativaRevisor)
                .param("co", camposOcultos).param("o", observacao).param("q", quando).param("id", id).update();
    }

    // ---------------------------------------------------------------- fila de revisão

    /** Filas da revisão. */
    public enum Fila {
        /** Ainda não revisados e que o motor não descartou. */
        PENDENTES("c.revisao_status in ('PENDENTE', 'NAO_NECESSARIA') and c.classificacao <> 'NAO_COMPATIVEL'"),
        /** Ainda não revisados que o motor classificou como NÃO COMPATÍVEL (conferência opcional). */
        NAO_COMPATIVEIS("c.revisao_status in ('PENDENTE', 'NAO_NECESSARIA') and c.classificacao = 'NAO_COMPATIVEL'"),
        APROVADAS("c.revisao_status = 'APROVADA'"),
        REJEITADAS("c.revisao_status = 'REJEITADA'");

        private final String condicao;

        Fila(String condicao) {
            this.condicao = condicao;
        }
    }

    /** Linha da fila de revisão, já com os dados do concurso e do documento. */
    public record ItemFila(long id, String nome, String especialidade, String classificacao, String revisaoStatus,
                           String classificacaoPublicada, LocalDateTime revisadoEm, long concursoId, String concurso,
                           String orgao, LocalDate inscricaoFim, boolean oficial, LocalDateTime encontradoEm) {
    }

    private static final String SELECT_FILA = """
            select c.id, c.nome, c.especialidade, c.classificacao, c.revisao_status, c.classificacao_publicada, c.revisado_em,
                k.id as concurso_id, k.nome as concurso, k.orgao, k.inscricao_fim, d.oficial, k.encontrado_em
            from cargo c
                join concurso k on k.id = c.concurso_id
                join documento d on d.id = c.documento_id
            """;

    /** A fazer: inscrições em aberto primeiro, pelo prazo mais próximo; as já encerradas vão para o fim. */
    private static final String ORDEM_A_FAZER =
            " order by case when k.inscricao_fim < :hoje then 1 else 0 end, k.inscricao_fim nulls last, k.id, c.indice";

    /** Pendentes: prazo de inscrição mais próximo primeiro. Revisados: os mais recentes primeiro. */
    public List<ItemFila> fila(Fila fila, LocalDate hoje) {
        String ordem = fila == Fila.APROVADAS || fila == Fila.REJEITADAS ? " order by c.revisado_em desc, c.id" : ORDEM_A_FAZER;
        return jdbc.sql(SELECT_FILA + " where " + fila.condicao + ordem).param("hoje", hoje).query(ItemFila.class).list();
    }

    public Map<Fila, Long> contarFilas() {
        Map<Fila, Long> contagem = new EnumMap<>(Fila.class);
        for (Fila f : Fila.values()) {
            contagem.put(f, jdbc.sql("select count(*) from cargo c where " + f.condicao).query(Long.class).single());
        }
        return contagem;
    }

    /** O que o público pode ver: aprovado na revisão e com classificação publicável. */
    public List<CargoLinha> publicados() {
        return jdbc.sql("""
                        select * from cargo
                        where revisao_status = 'APROVADA'
                          and classificacao_publicada in ('COMPATIVEL', 'POSSIVELMENTE_COMPATIVEL')
                        order by concurso_id, indice
                        """)
                .query(CargoLinha.class).list();
    }

    public record Pagina<T>(List<T> itens, long total, int pagina, int tamanho) {
    }

    public Pagina<CargoLinha> buscar(Filtro f, int pagina, int tamanho) {
        StringBuilder where = new StringBuilder(" from cargo c join concurso k on k.id = c.concurso_id where 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (f.uf() != null) {
            where.append(" and upper(k.uf) = :uf");
            p.put("uf", f.uf().toUpperCase());
        }
        if (f.municipio() != null) {
            where.append(" and lower(k.municipio) like :municipio");
            p.put("municipio", "%" + f.municipio().toLowerCase() + "%");
        }
        if (f.formacaoId() != null) {
            where.append(" and c.formacoes like :formacao");
            p.put("formacao", "%;" + f.formacaoId() + ";%");
        }
        if (f.especialidade() != null) {
            where.append(" and (lower(c.especialidade) like :esp or lower(c.nome) like :esp)");
            p.put("esp", "%" + f.especialidade().toLowerCase() + "%");
        }
        if (f.salarioMin() != null) {
            where.append(" and coalesce(c.remuneracao_total, c.salario_base, c.valor_quadro) >= :salMin");
            p.put("salMin", f.salarioMin());
        }
        if (f.salarioMax() != null) {
            where.append(" and coalesce(c.remuneracao_total, c.salario_base, c.valor_quadro) <= :salMax");
            p.put("salMax", f.salarioMax());
        }
        if (f.conselho() != null) {
            if (f.conselho().equalsIgnoreCase("NENHUM")) {
                where.append(" and c.conselho_exigido = 'NAO'");
            } else {
                where.append(" and c.conselho_tipo = :conselho");
                p.put("conselho", f.conselho().toUpperCase());
            }
        }
        if (Boolean.TRUE.equals(f.aceitaCrt())) {
            where.append(Boolean.TRUE.equals(f.incluirConselhoGenerico())
                    ? " and c.conselho_tipo in ('CRT', 'CRT_RJ', 'CFT', 'GENERICO')"
                    : " and c.conselho_tipo in ('CRT', 'CRT_RJ', 'CFT')");
        }
        if (Boolean.TRUE.equals(f.semConselho())) {
            where.append(" and c.conselho_exigido = 'NAO'");
        }
        if (f.status() != null) {
            where.append(switch (f.status()) {
                case INSCRICOES_ABERTAS -> " and k.inscricao_inicio <= :hoje and k.inscricao_fim >= :hoje";
                case EDITAL_PUBLICADO -> " and k.inscricao_inicio > :hoje";
                case INSCRICOES_ENCERRADAS -> " and k.inscricao_fim < :hoje";
                case PROVA_MARCADA -> " and k.inscricao_fim < :hoje and k.data_prova >= :hoje";
                default -> " and k.status = :status";
            });
            p.put("hoje", f.hoje());
            p.put("status", f.status().name());
        }
        if (Boolean.TRUE.equals(f.cadastroReserva())) {
            where.append(" and c.cadastro_reserva = true");
        }
        if (Boolean.TRUE.equals(f.vagasImediatas())) {
            where.append(" and coalesce(c.vagas_imediatas, c.vagas_total, 0) > 0");
        }
        if (f.banca() != null) {
            where.append(" and lower(k.banca) like :banca");
            p.put("banca", "%" + f.banca().toLowerCase() + "%");
        }
        if (f.orgao() != null) {
            where.append(" and lower(k.orgao) like :orgao");
            p.put("orgao", "%" + f.orgao().toLowerCase() + "%");
        }
        if (f.esfera() != null) {
            where.append(" and lower(k.esfera) = :esfera");
            p.put("esfera", f.esfera().toLowerCase());
        }
        if (f.inscricaoAte() != null) {
            where.append(" and k.inscricao_fim <= :inscAte");
            p.put("inscAte", f.inscricaoAte());
        }
        if (f.classificacoes() != null && !f.classificacoes().isEmpty()) {
            where.append(" and c.classificacao in (:classes)");
            p.put("classes", f.classificacoes().stream().map(Enum::name).toList());
        }

        long total = jdbc.sql("select count(*)" + where).params(p).query(Long.class).single();
        List<CargoLinha> itens = jdbc.sql("select c.*" + where
                        + " order by c.classificacao, k.inscricao_fim nulls last, c.id limit :lim offset :off")
                .params(p).param("lim", tamanho).param("off", pagina * tamanho)
                .query(CargoLinha.class).list();
        return new Pagina<>(itens, total, pagina, tamanho);
    }
}
