package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParserValoresTest {

    private static List<LocalDate> datas(String texto) {
        String norm = Normalizador.normalizar(texto);
        return ParserValores.datas(norm, 0, norm.length()).stream().map(ParserValores.DataEncontrada::data).toList();
    }

    @Test
    void anoEmprestadoDaDataSeguinte() {
        assertThat(datas("de 10/09 a 15/10/2026")).containsExactly(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 15));
    }

    @Test
    void viradaDeAno() {
        assertThat(datas("de 20/12 a 10/01/2027")).containsExactly(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10));
    }

    @Test
    void dataPorExtenso() {
        assertThat(datas("no dia 1º de outubro de 2026")).containsExactly(LocalDate.of(2026, 10, 1));
    }

    @Test
    void dataSemAnoNaFraseNaoEhInventada() {
        assertThat(datas("a partir de 10/09")).isEmpty();
    }

    @Test
    void numerosDeLeiNaoViramData() {
        assertThat(datas("Lei nº 13.639/2018 e Edital nº 01/2026")).isEmpty();
    }

    @Test
    void valoresEmReais() {
        String norm = Normalizador.normalizar("R$ 4.250,00 e R$ 75,00");
        assertThat(ParserValores.valores(norm, 0, norm.length()))
                .extracting(v -> v.valor().toPlainString())
                .containsExactly("4250.00", "75.00");
    }
}
