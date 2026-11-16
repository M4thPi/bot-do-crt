package br.gov.crtrj.concursos.motor.dominio;

/**
 * Onde, no documento oficial, um dado foi encontrado.
 *
 * @param pagina  página (1-based) do documento
 * @param secao   seção de nível mais alto (ex.: "Anexo I"), quando identificada
 * @param item    item numerado mais próximo (ex.: "4.2.3" ou "3.1.h"), quando identificado
 * @param trecho  cópia literal do texto do documento
 * @param metodo  como o dado foi extraído
 * @param score   similaridade entre o trecho e o texto da página (1.0 = literal)
 */
public record Evidencia(int pagina, String secao, String item, String trecho, MetodoExtracao metodo, double score) {

    public static final double LIMIAR_VERIFICACAO = 0.92;

    public boolean verificada() {
        return score >= LIMIAR_VERIFICACAO;
    }

    public Evidencia comScore(double novoScore) {
        return new Evidencia(pagina, secao, item, trecho, metodo, novoScore);
    }

    /** "p. 37, Anexo I, item 4.2.3" */
    public String localizacao() {
        StringBuilder sb = new StringBuilder("p. ").append(pagina);
        if (secao != null && !secao.isBlank()) {
            sb.append(", ").append(secao);
        }
        if (item != null && !item.isBlank()) {
            sb.append(", item ").append(item);
        }
        return sb.toString();
    }
}
