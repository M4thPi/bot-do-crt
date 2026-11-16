package br.gov.crtrj.concursos.motor.leitor;

import java.util.List;

/**
 * @param paginasBrutas   texto de cada página, na ordem
 * @param paginasSemTexto páginas sem camada de texto (precisariam de OCR)
 */
public record DocumentoLido(List<String> paginasBrutas, List<Integer> paginasSemTexto) {

    public boolean majoritariamenteEscaneado() {
        return !paginasBrutas.isEmpty() && paginasSemTexto.size() * 2 > paginasBrutas.size();
    }
}
