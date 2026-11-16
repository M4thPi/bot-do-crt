package br.gov.crtrj.concursos.motor.extracao;

/**
 * Trecho [inicio, fim) do texto global onde procurar requisitos.
 *
 * @param rotulo rótulo normalizado que abriu a faixa ("requisitos", "escolaridade"...), ou null
 *               quando o bloco não tem rótulo e a faixa é o próprio conteúdo do bloco
 */
public record Faixa(int inicio, int fim, String rotulo) {

    public boolean rotulada() {
        return rotulo != null;
    }
}
