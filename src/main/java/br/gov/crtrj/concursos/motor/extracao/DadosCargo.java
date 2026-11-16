package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;

import java.math.BigDecimal;

/**
 * Valores objetivos de um cargo.
 *
 * @param valorQuadro valor em reais encontrado no bloco do cargo sem rótulo que diga se é
 *                    salário-base ou remuneração total (fica AMBIGUO, nunca vira salário-base)
 */
public record DadosCargo(Campo<Integer> vagasTotal, Campo<Integer> vagasImediatas, Campo<Integer> vagasAmpla,
                         Campo<Integer> vagasPcd, Campo<Boolean> cadastroReserva,
                         Campo<BigDecimal> salarioBase, Campo<BigDecimal> remuneracaoTotal, Campo<BigDecimal> valorQuadro,
                         Campo<Integer> cargaHorariaSemanal, Campo<String> cnh, Campo<String> experiencia) {
}
