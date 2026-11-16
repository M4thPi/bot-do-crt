package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.MomentoRegistro;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;

/**
 * O que o edital diz sobre registro em conselho — sempre a partir do texto literal.
 *
 * @param exigido            SIM/NAO só quando o edital diz; NAO_INFORMADO caso contrário
 * @param literal            conselho como escrito no edital ("respectivo conselho de classe"), com evidência
 * @param tipo               normalização do literal; null quando nenhum conselho foi citado
 * @param exigeCrtRj         SIM só com menção literal ao CRT-RJ; NAO só quando o edital nomeia outro conselho específico
 * @param registroAtivo      SIM quando o edital pede registro ativo/regular
 * @param momento            quando o registro é exigido
 * @param exigenciaGenerica  a exigência vem de uma cláusula geral do edital, não do requisito do cargo
 */
public record InfoConselho(SimNao exigido, Campo<String> literal, TipoConselho tipo, SimNao exigeCrtRj,
                           SimNao registroAtivo, MomentoRegistro momento, boolean exigenciaGenerica) {

    public static InfoConselho naoIdentificado() {
        return new InfoConselho(SimNao.NAO_INFORMADO, Campo.naoEncontrado(), null, SimNao.NAO_INFORMADO,
                SimNao.NAO_INFORMADO, MomentoRegistro.NAO_INFORMADO, false);
    }

    public boolean exigeSistemaCftCrt() {
        return exigido == SimNao.SIM && tipo != null && tipo.sistemaCftCrt() && !exigenciaGenerica;
    }
}
