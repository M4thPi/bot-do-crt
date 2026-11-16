package br.gov.crtrj.concursos.motor.dominio;

public enum SimNao {
    SIM("Sim"),
    NAO("Não"),
    NAO_INFORMADO("Não informado");

    private final String rotulo;

    SimNao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
