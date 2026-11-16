package br.gov.crtrj.concursos.motor.classificacao;

import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;

/**
 * @param evidencia          trecho que fundamenta a classificação (null quando nada foi localizado)
 * @param revisaoNecessaria  deve passar por revisão humana antes de ser divulgada
 */
public record ResultadoClassificacao(Classificacao classificacao, String motivo, Evidencia evidencia,
                                     boolean revisaoNecessaria) {
}
