package br.gov.crtrj.concursos.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Cargo como a API devolve na busca. Campos nulos = não identificados no edital consultado. */
public record CargoResposta(long id, long concursoId, String concurso, String orgao, String esfera, String local,
                            String banca, String cargo, String especialidade, String escolaridade, List<String> formacoes,
                            Vagas vagas, Remuneracao remuneracao, Integer cargaHorariaSemanal, Conselho conselho,
                            Inscricao inscricao, LocalDate dataProva, String statusConcurso, String classificacao,
                            String justificativa, String revisao, Documento edital) {

    public record Vagas(Integer total, Integer imediatas, Integer amplaConcorrencia, Integer pcd, boolean cadastroReserva) {
    }

    /**
     * @param valorNoQuadroAConfirmar valor encontrado sem rótulo que diga se é salário-base ou total
     */
    public record Remuneracao(BigDecimal salarioBase, BigDecimal remuneracaoTotal, BigDecimal valorNoQuadroAConfirmar,
                              String observacao) {
    }

    public record Conselho(String exigido, String tipo, String literal, String exigeCrtRj, String registroAtivo,
                           String momento, boolean exigenciaGenerica) {
    }

    public record Inscricao(LocalDate inicio, LocalDate fim, BigDecimal taxa, String site) {
    }

    /**
     * @param url     URL de origem (null em envio manual)
     * @param arquivo cópia guardada pelo sistema
     */
    public record Documento(long id, String nome, String url, boolean fonteOficial, String tipoFonte, String arquivo) {
    }
}
