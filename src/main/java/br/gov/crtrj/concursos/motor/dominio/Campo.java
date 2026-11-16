package br.gov.crtrj.concursos.motor.dominio;

/**
 * Um dado extraído do edital. Ou tem valor com evidência, ou está explicitamente ausente —
 * nunca é preenchido por suposição.
 */
public record Campo<T>(T valor, StatusCampo status, Evidencia evidencia) {

    public static final String NAO_IDENTIFICADO = "Não identificado no edital consultado.";

    public Campo {
        if (status == StatusCampo.ENCONTRADO && (valor == null || evidencia == null)) {
            throw new IllegalArgumentException("Campo encontrado exige valor e evidência");
        }
    }

    public static <T> Campo<T> encontrado(T valor, Evidencia evidencia) {
        return new Campo<>(valor, StatusCampo.ENCONTRADO, evidencia);
    }

    /** Há um valor no documento, mas não dá para afirmar o que ele representa. */
    public static <T> Campo<T> ambiguo(T valor, Evidencia evidencia) {
        return new Campo<>(valor, StatusCampo.AMBIGUO, evidencia);
    }

    public static <T> Campo<T> naoEncontrado() {
        return new Campo<>(null, StatusCampo.NAO_ENCONTRADO, null);
    }

    public boolean presente() {
        return status == StatusCampo.ENCONTRADO;
    }

    public T valorOu(T padrao) {
        return presente() ? valor : padrao;
    }
}
