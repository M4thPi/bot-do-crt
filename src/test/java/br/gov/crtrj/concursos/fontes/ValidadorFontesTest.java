package br.gov.crtrj.concursos.fontes;

import org.junit.jupiter.api.Test;

import static br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte.AGREGADOR;
import static br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte.BANCA;
import static br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte.DESCONHECIDA;
import static br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte.ENVIO_MANUAL;
import static br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte.ORGAO_OFICIAL;
import static org.assertj.core.api.Assertions.assertThat;

class ValidadorFontesTest {

    private final ValidadorFontes validador = ValidadorFontes.padrao();

    @Test
    void orgaosPublicosEUniversidades() {
        assertThat(validador.classificar("https://www.niteroi.rj.gov.br/edital.pdf")).isEqualTo(ORGAO_OFICIAL);
        assertThat(validador.classificar("https://portal.coseac.uff.br/wp-content/uploads/edital.pdf")).isEqualTo(ORGAO_OFICIAL);
        assertThat(validador.classificar("https://www.cepuerj.uerj.br/uploads/x.pdf")).isEqualTo(ORGAO_OFICIAL);
        assertThat(validador.classificar("https://portal.ifrj.edu.br/edital.pdf")).isEqualTo(ORGAO_OFICIAL);
    }

    @Test
    void bancas() {
        assertThat(validador.classificar("https://anexos-r2.selecao.net.br/uploads/331/x.pdf")).isEqualTo(BANCA);
        assertThat(validador.classificar("https://conhecimento.fgv.br/concursos/x")).isEqualTo(BANCA);
    }

    @Test
    void agregadoresNuncaSaoOficiais() {
        assertThat(validador.classificar("https://arq.pciconcursos.com.br/x/edital.pdf")).isEqualTo(AGREGADOR);
        assertThat(validador.classificar("https://jcconcursos.com.br/media/uploads/anexos/edital.pdf")).isEqualTo(AGREGADOR);
    }

    @Test
    void semUrlOuDominioDesconhecido() {
        assertThat(validador.classificar(null)).isEqualTo(ENVIO_MANUAL);
        assertThat(validador.classificar("https://www.exemplo.com.br/edital.pdf")).isEqualTo(DESCONHECIDA);
        // "uff.br" não pode casar com "stuff.br"
        assertThat(validador.classificar("https://stuff.br/edital.pdf")).isEqualTo(DESCONHECIDA);
    }
}
