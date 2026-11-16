package br.gov.crtrj.concursos.motor;

/**
 * De onde veio o documento analisado.
 *
 * @param nome          nome de exibição (arquivo ou URL) usado quando o nº do edital não é encontrado
 * @param url           URL de origem, ou null em envio manual
 * @param fonteOficial  resultado do validador de fontes
 * @param tipoFonte     classificação da fonte
 */
public record OrigemDocumento(String nome, String url, boolean fonteOficial, String tipoFonte) {
}
