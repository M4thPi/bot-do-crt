package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.MomentoRegistro;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exemplos do item 4 da especificação: o bot informa o que está escrito, sem presumir. */
class ExtratorConselhoTest {

    private final ExtratorConselho extrator = new ExtratorConselho();

    private InfoConselho analisar(String requisito) {
        TextoDocumento texto = TextoDocumento.de(List.of("REQUISITOS: " + requisito + "\n"));
        return extrator.analisar(texto, List.of(new Faixa("REQUISITOS: ".length(), texto.original().length(), "requisitos")), List.of());
    }

    @Test
    void conselhoCompetenteNaoViraCrtRj() {
        InfoConselho c = analisar("Curso técnico em Eletrotécnica e registro no conselho profissional competente.");
        assertThat(c.exigido()).isEqualTo(SimNao.SIM);
        assertThat(c.tipo()).isEqualTo(TipoConselho.GENERICO);
        assertThat(c.literal().valor()).isEqualTo("conselho profissional competente");
        assertThat(c.exigeCrtRj()).isEqualTo(SimNao.NAO_INFORMADO);
    }

    @Test
    void crtComRegistroAtivo() {
        InfoConselho c = analisar("Curso técnico em Mecânica e registro ativo no CRT.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CRT);
        assertThat(c.registroAtivo()).isEqualTo(SimNao.SIM);
        assertThat(c.exigeCrtRj()).isEqualTo(SimNao.NAO_INFORMADO);
    }

    @Test
    void crtRjLiteral() {
        InfoConselho c = analisar("Curso técnico em Eletrônica e registro no CRT/RJ, a ser apresentado no ato da posse.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CRT_RJ);
        assertThat(c.exigeCrtRj()).isEqualTo(SimNao.SIM);
        assertThat(c.momento()).isEqualTo(MomentoRegistro.POSSE);
    }

    @Test
    void conselhoRegionalDosTecnicosDoRioPorExtenso() {
        InfoConselho c = analisar("Registro no Conselho Regional dos Técnicos Industriais do Estado do Rio de Janeiro.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CRT_RJ);
    }

    @Test
    void respectivoConselhoRegionalDosTecnicosEhCrtSemUf() {
        InfoConselho c = analisar("Curso técnico em Eletrotécnica e registro no respectivo Conselho Regional dos Técnicos Industriais.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CRT);
        assertThat(c.literal().valor()).isEqualTo("Conselho Regional dos Técnicos Industriais");
        assertThat(c.exigeCrtRj()).isEqualTo(SimNao.NAO_INFORMADO);
    }

    @Test
    void cftFederal() {
        InfoConselho c = analisar("Curso técnico em Edificações com registro no Conselho Federal dos Técnicos Industriais.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CFT);
    }

    @Test
    void creaDizQueNaoEhCrtRj() {
        InfoConselho c = analisar("Curso técnico em Eletrotécnica e registro no CREA.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.CREA);
        assertThat(c.exigeCrtRj()).isEqualTo(SimNao.NAO);
    }

    @Test
    void crtrDeRadiologiaNaoEhCrt() {
        InfoConselho c = analisar("Curso técnico em Radiologia e registro no CRTR.");
        assertThat(c.tipo()).isEqualTo(TipoConselho.OUTRO);
    }

    @Test
    void semMencaoFicaNaoIdentificado() {
        InfoConselho c = analisar("Curso técnico em Mecatrônica.");
        assertThat(c.literal().presente()).isFalse();
        assertThat(c.exigido()).isEqualTo(SimNao.NAO_INFORMADO);
    }
}
