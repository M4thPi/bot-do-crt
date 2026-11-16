package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação pt-BR dos textos da API. Valor ausente vira a frase padrão, nunca um palpite. */
final class Formato {

    static final String NI = Campo.NAO_IDENTIFICADO;

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private Formato() {
    }

    static String data(LocalDate d) {
        return d == null ? null : DATA.format(d);
    }

    static String dataHora(LocalDateTime d) {
        return d == null ? null : DATA_HORA.format(d);
    }

    /** DecimalFormat não é thread-safe: um por chamada. */
    static String dinheiro(BigDecimal valor) {
        return valor == null ? null
                : "R$ " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR"))).format(valor);
    }

    static String rotulo(String classificacao) {
        return classificacao == null ? null : Classificacao.valueOf(classificacao).rotulo();
    }

    /** "p. 37, Anexo I, item 4.2.3" — mesmo formato de {@code Evidencia.localizacao()}. */
    static String local(int pagina, String secao, String item) {
        StringBuilder sb = new StringBuilder("p. ").append(pagina);
        if (secao != null && !secao.isBlank()) {
            sb.append(", ").append(secao);
        }
        if (item != null && !item.isBlank()) {
            sb.append(", item ").append(item);
        }
        return sb.toString();
    }

    static String ouNi(String s) {
        return s == null || s.isBlank() ? NI : s;
    }
}
