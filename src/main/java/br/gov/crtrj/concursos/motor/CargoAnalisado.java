package br.gov.crtrj.concursos.motor;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.extracao.DadosCargo;
import br.gov.crtrj.concursos.motor.extracao.FormacaoEncontrada;
import br.gov.crtrj.concursos.motor.extracao.InfoConselho;

import java.math.BigDecimal;
import java.util.List;

/**
 * Um cargo analisado, com a classificação e a evidência que a sustenta.
 *
 * @param requisito  trecho do requisito do cargo como um todo
 * @param alertas    sinais informativos (ex.: edital exige CREA para técnico industrial)
 */
public record CargoAnalisado(Campo<String> nome, Campo<String> especialidade, Campo<String> escolaridade,
                             List<FormacaoEncontrada> formacoes, Evidencia requisito, InfoConselho conselho,
                             DadosCargo dados, Campo<BigDecimal> taxaInscricao,
                             Classificacao classificacao, String motivo, Evidencia evidenciaClassificacao,
                             boolean revisaoNecessaria, List<String> alertas) {
}
