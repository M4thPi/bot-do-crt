package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Dados do concurso como um todo (valem para todos os cargos do edital). */
public record DadosConcurso(Campo<String> orgao, Campo<String> numeroEdital, Campo<String> banca,
                            Campo<String> esfera, Campo<String> uf, Campo<String> municipio,
                            Campo<LocalDate> inscricaoInicio, Campo<LocalDate> inscricaoFim,
                            List<Taxa> taxas, Campo<String> isencao, Campo<LocalDate> isencaoFim,
                            Campo<String> siteInscricao, Campo<LocalDate> dataProva, List<Campo<String>> etapas) {

    /**
     * @param contexto texto normalizado em volta do valor, usado para casar a taxa com o nível do cargo
     */
    public record Taxa(Campo<BigDecimal> valor, String contexto) {
    }
}
