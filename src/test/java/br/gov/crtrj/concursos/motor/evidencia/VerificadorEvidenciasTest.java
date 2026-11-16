package br.gov.crtrj.concursos.motor.evidencia;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificadorEvidenciasTest {

    private final VerificadorEvidencias verificador = new VerificadorEvidencias();
    private final TextoDocumento texto = TextoDocumento.de(List.of(
            "Página um.\n",
            "3.2 O vencimento básico do cargo é de R$ 4.250,00 mensais.\nOutros itens.\n"));

    private static Evidencia ev(int pagina, String trecho) {
        return new Evidencia(pagina, null, "3.2", trecho, MetodoExtracao.IA, 0);
    }

    @Test
    void aceitaTrechoLiteralComValorCorreto() {
        Campo<BigDecimal> campo = Campo.encontrado(new BigDecimal("4250.00"),
                ev(2, "O vencimento básico do cargo é de R$ 4.250,00 mensais."));
        Campo<BigDecimal> verificado = verificador.verificar(texto, campo);
        assertThat(verificado.presente()).isTrue();
        assertThat(verificado.evidencia().score()).isEqualTo(1.0);
    }

    @Test
    void toleraDiferencaDeEspacosEAcentos() {
        Campo<BigDecimal> campo = Campo.encontrado(new BigDecimal("4250.00"),
                ev(2, "O vencimento basico do cargo e de R$  4.250,00 mensais."));
        assertThat(verificador.verificar(texto, campo).presente()).isTrue();
    }

    @Test
    void rejeitaValorQueNaoEstaNoTrecho() {
        Campo<BigDecimal> campo = Campo.encontrado(new BigDecimal("4520.00"),
                ev(2, "O vencimento básico do cargo é de R$ 4.250,00 mensais."));
        assertThat(verificador.verificar(texto, campo).presente()).isFalse();
    }

    @Test
    void rejeitaTrechoInventado() {
        Campo<String> campo = Campo.encontrado("CRT-RJ", ev(2, "Exige registro ativo no CRT-RJ."));
        assertThat(verificador.verificar(texto, campo).presente()).isFalse();
    }

    @Test
    void rejeitaPaginaErrada() {
        Campo<String> campo = Campo.encontrado("x", ev(9, "O vencimento básico do cargo"));
        assertThat(verificador.verificar(texto, campo).presente()).isFalse();
    }

    @Test
    void normalizadorPreservaComprimento() {
        String original = "Técnico em Eletrotécnica – nº 01/2026 “ÇÃO”";
        assertThat(Normalizador.normalizar(original)).hasSameSizeAs(original)
                .startsWith("tecnico em eletrotecnica");
    }
}
