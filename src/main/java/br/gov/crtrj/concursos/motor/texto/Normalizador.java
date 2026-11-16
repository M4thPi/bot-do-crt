package br.gov.crtrj.concursos.motor.texto;

import java.text.Normalizer;

/**
 * Normalização caractere a caractere (minúsculas, sem acento). Mantém o mesmo comprimento do
 * texto original, então um índice encontrado no texto normalizado vale também no original —
 * é isso que permite devolver o trecho literal do edital como evidência.
 */
public final class Normalizador {

    private static final char[] TABELA = new char[Character.MAX_VALUE + 1];

    static {
        for (int c = 0; c <= Character.MAX_VALUE; c++) {
            TABELA[c] = calcular((char) c);
        }
    }

    private Normalizador() {
    }

    private static char calcular(char c) {
        if (c < 128) {
            return Character.toLowerCase(c);
        }
        if (Character.isSurrogate(c)) {
            return c;
        }
        if (Character.isSpaceChar(c)) {
            return ' ';
        }
        String decomposto = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKD);
        for (int i = 0; i < decomposto.length(); i++) {
            char x = decomposto.charAt(i);
            if (Character.getType(x) != Character.NON_SPACING_MARK) {
                return Character.toLowerCase(x);
            }
        }
        return c;
    }

    /** Mesmo comprimento do original. */
    public static String normalizar(String texto) {
        char[] saida = new char[texto.length()];
        for (int i = 0; i < texto.length(); i++) {
            saida[i] = TABELA[texto.charAt(i)];
        }
        return new String(saida);
    }

    /** Normaliza e colapsa espaços — para comparações, não preserva posições. */
    public static String compacto(String texto) {
        return normalizar(texto).replaceAll("\\s+", " ").trim();
    }

    /** Chave para comparar nomes (cargos, órgãos): só letras e dígitos separados por espaço. */
    public static String chave(String texto) {
        return normalizar(texto).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
