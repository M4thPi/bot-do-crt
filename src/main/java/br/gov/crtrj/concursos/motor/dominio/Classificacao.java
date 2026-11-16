package br.gov.crtrj.concursos.motor.dominio;

public enum Classificacao {
    COMPATIVEL("COMPATÍVEL"),
    POSSIVELMENTE_COMPATIVEL("POSSIVELMENTE COMPATÍVEL"),
    NAO_COMPATIVEL("NÃO COMPATÍVEL"),
    NAO_FOI_POSSIVEL_CONFIRMAR("NÃO FOI POSSÍVEL CONFIRMAR");

    private final String rotulo;

    Classificacao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
