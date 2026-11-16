package br.gov.crtrj.concursos.motor.catalogo;

import java.util.List;

/**
 * @param industrial true = técnica industrial; false = outra área; null = ainda não validada
 */
public record EntradaFormacao(String id, String nome, Boolean industrial, List<String> sinonimos) {

    public EntradaFormacao {
        sinonimos = sinonimos == null ? List.of() : List.copyOf(sinonimos);
    }
}
