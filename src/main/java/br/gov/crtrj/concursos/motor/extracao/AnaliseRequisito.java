package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;

import java.util.List;

/**
 * @param localizado        o requisito do cargo foi encontrado no documento
 * @param rotulado          veio de um trecho rotulado ("Requisitos:", "Escolaridade:"...)
 * @param trecho            evidência do requisito como um todo
 * @param formacoes         formações do catálogo citadas no requisito
 * @param tecnicoGenerico   "curso técnico" sem área identificada (só quando não há formação do catálogo)
 * @param qualquerArea      o edital diz "qualquer área/curso técnico"
 * @param escolaridade      nível de escolaridade citado
 * @param qualificacao      curso de qualificação/NR-10 citado (não é habilitação técnica)
 * @param naoCatalogadas    "Técnico em X" citados no requisito que o catálogo não conhece (texto literal)
 */
public record AnaliseRequisito(boolean localizado, boolean rotulado, Evidencia trecho,
                               List<FormacaoEncontrada> formacoes, Evidencia tecnicoGenerico, boolean qualquerArea,
                               Campo<String> escolaridade, Evidencia qualificacao, List<String> naoCatalogadas) {

    public AnaliseRequisito {
        naoCatalogadas = naoCatalogadas == null ? List.of() : List.copyOf(naoCatalogadas);
    }

    public static AnaliseRequisito naoLocalizado() {
        return new AnaliseRequisito(false, false, null, List.of(), null, false, Campo.naoEncontrado(), null, List.of());
    }
}
