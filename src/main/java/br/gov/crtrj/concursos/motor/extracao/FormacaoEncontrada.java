package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.catalogo.EntradaFormacao;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;

/**
 * @param entrada       formação do catálogo
 * @param textoLiteral  como aparece no edital ("Eletrotécnica")
 * @param evidencia     frase do requisito que cita a formação
 */
public record FormacaoEncontrada(EntradaFormacao entrada, String textoLiteral, Evidencia evidencia) {
}
