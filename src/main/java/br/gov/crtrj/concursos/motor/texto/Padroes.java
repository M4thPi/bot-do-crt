package br.gov.crtrj.concursos.motor.texto;

import java.util.regex.Pattern;

/**
 * Compila regex sobre texto normalizado tolerando quebra de linha entre palavras: todo espaço
 * literal fora de classe de caracteres ([...]) vira "\s+". Editais em PDF quebram frases em
 * qualquer ponto ("Conselho Regional de\nEnfermagem").
 */
public final class Padroes {

    private Padroes() {
    }

    public static Pattern compilar(String regex) {
        StringBuilder sb = new StringBuilder(regex.length() + 16);
        boolean emClasse = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                sb.append(c).append(regex.charAt(++i));
                continue;
            }
            if (c == '[') {
                emClasse = true;
            } else if (c == ']') {
                emClasse = false;
            }
            if (c == ' ' && !emClasse) {
                sb.append("\\s+");
                while (i + 1 < regex.length() && regex.charAt(i + 1) == ' ') {
                    i++;
                }
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.toString());
    }
}
