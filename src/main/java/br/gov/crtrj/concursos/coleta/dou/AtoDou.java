package br.gov.crtrj.concursos.coleta.dou;

import java.time.LocalDate;

/**
 * Um ato publicado no Diário Oficial da União, como vem no XML do INLABS.
 *
 * @param orgao  hierarquia do órgão ("Ministério da Educação/Universidade Federal Fluminense/...")
 * @param titulo identificação do ato ("EDITAL Nº 96, DE 30 DE SETEMBRO DE 2026")
 * @param texto  texto do ato, sem as marcas de HTML
 * @param link   página do ato para leitura humana
 */
public record AtoDou(String id, LocalDate data, String secao, String tipo, String orgao, String titulo,
                     String ementa, String texto, String link) {
}
