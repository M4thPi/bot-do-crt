package br.gov.crtrj.concursos.motor.dominio;

import java.time.LocalDate;

public enum StatusConcurso {
    PREVISTO("Previsto"),
    EDITAL_PUBLICADO("Edital publicado"),
    INSCRICOES_ABERTAS("Inscrições abertas"),
    INSCRICOES_ENCERRADAS("Inscrições encerradas"),
    PROVA_MARCADA("Prova marcada"),
    RESULTADO_DIVULGADO("Resultado divulgado"),
    HOMOLOGADO("Concurso homologado"),
    SUSPENSO("Concurso suspenso"),
    CANCELADO("Concurso cancelado");

    private final String rotulo;

    StatusConcurso(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /**
     * Status derivado só das datas do edital. Estados que dependem de comunicados
     * (suspensão, homologação...) prevalecem quando já registrados.
     */
    public static StatusConcurso porDatas(StatusConcurso registrado, LocalDate inicio, LocalDate fim,
                                          LocalDate prova, LocalDate hoje) {
        if (registrado == SUSPENSO || registrado == CANCELADO || registrado == HOMOLOGADO
                || registrado == RESULTADO_DIVULGADO) {
            return registrado;
        }
        if (inicio != null && hoje.isBefore(inicio)) {
            return EDITAL_PUBLICADO;
        }
        if (fim != null && !hoje.isAfter(fim) && (inicio == null || !hoje.isBefore(inicio))) {
            return inicio != null ? INSCRICOES_ABERTAS : EDITAL_PUBLICADO;
        }
        if (fim != null && hoje.isAfter(fim)) {
            return prova != null && !hoje.isAfter(prova) ? PROVA_MARCADA : INSCRICOES_ENCERRADAS;
        }
        return registrado != null ? registrado : EDITAL_PUBLICADO;
    }
}
