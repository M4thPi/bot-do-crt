package br.gov.crtrj.concursos.motor.extracao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Leitura de valores em reais e datas a partir de texto normalizado. */
public final class ParserValores {

    public static final Pattern DINHEIRO = Pattern.compile(
            "r\\$\\s*(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{2})?|\\d+(?:,\\d{2})?)");

    private static final Pattern DATA_NUMERICA = Pattern.compile(
            "(?<![\\d/.,])(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}|\\d{2}))?(?![\\d/])");
    private static final Pattern DATA_EXTENSO = Pattern.compile(
            "(?<!\\d)(\\d{1,2})o?\\s+de\\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)"
                    + "(?:\\s+de\\s+(\\d{4}))?");
    private static final List<String> MESES = List.of("janeiro", "fevereiro", "marco", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro");

    /** Distância máxima (em caracteres) para "emprestar" o ano de uma data seguinte: "10/09 a 15/10/2026". */
    private static final int ALCANCE_ANO = 150;

    private ParserValores() {
    }

    public static BigDecimal dinheiro(String numero) {
        return new BigDecimal(numero.replace(".", "").replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
    }

    /** Valores em reais encontrados no texto (normalizado). */
    public static List<ValorEncontrado> valores(String normalizado, int de, int ate) {
        List<ValorEncontrado> lista = new ArrayList<>();
        Matcher m = DINHEIRO.matcher(normalizado).region(de, ate);
        while (m.find()) {
            lista.add(new ValorEncontrado(m.start(), m.end(), dinheiro(m.group(1))));
        }
        return lista;
    }

    public record ValorEncontrado(int inicio, int fim, BigDecimal valor) {
    }

    public record DataEncontrada(int inicio, int fim, LocalDate data) {
    }

    /** Datas no intervalo [de, ate) do texto normalizado, em ordem. Datas sem ano só entram se o ano estiver na mesma frase. */
    public static List<DataEncontrada> datas(String normalizado, int de, int ate) {
        record Bruta(int inicio, int fim, int dia, int mes, Integer ano) {
        }
        List<Bruta> brutas = new ArrayList<>();
        Matcher m = DATA_NUMERICA.matcher(normalizado).region(de, ate);
        while (m.find()) {
            brutas.add(new Bruta(m.start(), m.end(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? null : ano(m.group(3))));
        }
        m = DATA_EXTENSO.matcher(normalizado).region(de, ate);
        while (m.find()) {
            brutas.add(new Bruta(m.start(), m.end(), Integer.parseInt(m.group(1)), MESES.indexOf(m.group(2)) + 1,
                    m.group(3) == null ? null : Integer.parseInt(m.group(3))));
        }
        brutas.sort(Comparator.comparingInt(Bruta::inicio));

        List<DataEncontrada> datas = new ArrayList<>();
        for (int i = 0; i < brutas.size(); i++) {
            Bruta b = brutas.get(i);
            if (b.mes() < 1 || b.mes() > 12 || b.dia() < 1 || b.dia() > 31) {
                continue;
            }
            Integer ano = b.ano();
            if (ano == null) {
                for (int j = i + 1; j < brutas.size() && brutas.get(j).inicio() - b.fim() <= ALCANCE_ANO; j++) {
                    Bruta seguinte = brutas.get(j);
                    if (seguinte.ano() != null) {
                        ano = seguinte.mes() < b.mes() ? seguinte.ano() - 1 : seguinte.ano();
                        break;
                    }
                }
            }
            if (ano == null) {
                continue;
            }
            try {
                datas.add(new DataEncontrada(b.inicio(), b.fim(), LocalDate.of(ano, b.mes(), b.dia())));
            } catch (DateTimeException e) {
                // data impossível (31/02): ignora
            }
        }
        return datas;
    }

    private static int ano(String texto) {
        int valor = Integer.parseInt(texto);
        return texto.length() == 2 ? 2000 + valor : valor;
    }
}
