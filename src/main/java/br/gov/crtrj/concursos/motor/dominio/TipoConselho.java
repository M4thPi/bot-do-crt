package br.gov.crtrj.concursos.motor.dominio;

/** Conselho citado no edital, normalizado a partir do texto literal. */
public enum TipoConselho {
    CRT_RJ,
    CRT,
    CFT,
    CREA,
    MTE,
    /** Outro conselho nomeado explicitamente (CRQ, COREN, CRTR...). */
    OUTRO,
    /** "respectivo conselho de classe", "órgão competente"... */
    GENERICO;

    public boolean sistemaCftCrt() {
        return this == CRT_RJ || this == CRT || this == CFT;
    }
}
