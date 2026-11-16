package br.gov.crtrj.concursos.coleta;

/**
 * Um concurso visto numa fonte de descoberta (agregador, site de banca, diário oficial). Não é
 * fato: só indica onde procurar o edital oficial.
 *
 * @param url            página onde o concurso foi visto (notícia, página do concurso na banca, ato do diário)
 * @param orgao          nome do órgão como a fonte escreve
 * @param uf             UF informada pela fonte ("RJ", ou "Nacional")
 * @param escolaridade   níveis informados pela fonte ("Médio / Técnico / Superior")
 * @param inscricaoAte   prazo como a fonte escreve ("13/10/2026", "Prorrogado até")
 * @param texto          texto do ato, quando a fonte é um diário oficial (o robô procura nele o site do edital)
 * @param motivoDescarte por que a pista não interessa (fora do RJ, sem nível técnico...); null = processar
 */
public record PistaEncontrada(String fonte, String url, String titulo, String orgao, String uf, String vagas,
                              String cargos, String escolaridade, String inscricaoAte, String texto,
                              String motivoDescarte) {

    public PistaEncontrada(String fonte, String url, String titulo, String orgao, String uf, String vagas,
                           String cargos, String escolaridade, String inscricaoAte) {
        this(fonte, url, titulo, orgao, uf, vagas, cargos, escolaridade, inscricaoAte, null, null);
    }

    public PistaEncontrada descartada(String motivo) {
        return new PistaEncontrada(fonte, url, titulo, orgao, uf, vagas, cargos, escolaridade, inscricaoAte, texto, motivo);
    }
}
