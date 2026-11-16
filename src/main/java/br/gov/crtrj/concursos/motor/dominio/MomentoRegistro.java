package br.gov.crtrj.concursos.motor.dominio;

/** Em que momento o edital exige o registro no conselho. */
public enum MomentoRegistro {
    INSCRICAO("Inscrição"),
    POSSE("Posse"),
    CONTRATACAO("Contratação"),
    NAO_INFORMADO("Não informado");

    private final String rotulo;

    MomentoRegistro(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
