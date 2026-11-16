package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static br.gov.crtrj.concursos.coleta.WebFalsa.linhaPci;
import static org.assertj.core.api.Assertions.assertThat;

class ColetaUnidadeTest {

    private static final String LISTAGEM = "https://www.pciconcursos.com.br/concursos/sudeste/";
    private static final String NOTICIA = "https://www.pciconcursos.com.br/noticias/ufrrj-publica-edital";

    // ------------------------------------------------------------------ listagem do PCI

    @Test
    void listagemFicaSoComUfsPedidas() throws IOException {
        WebFalsa web = new WebFalsa().html(LISTAGEM, "<html><body>"
                + linhaPci("da", "https://www.pciconcursos.com.br/noticias/detran-sp", "DETRAN - SP", "SP",
                "145 vagas até R$ 5.702,18", "Agente", "Superior", "07/10/2026")
                + linhaPci("na", NOTICIA, "UFRRJ - Universidade Federal Rural do Rio de Janeiro", "RJ",
                "13 vagas até R$ 3.181,39", "Vários Cargos", "Médio / Técnico", "13/10/2026")
                + linhaPci("da", "https://www.pciconcursos.com.br/noticias/uerj-professor", "UERJ", "RJ",
                "1 vaga até R$ 6.950,85", "Professor Adjunto", "Superior", "30/10/2026")
                + "</body></html>");

        List<PistaEncontrada> pistas = new FontePci(web, List.of(LISTAGEM), Set.of("RJ")).listar();

        assertThat(pistas).extracting(PistaEncontrada::orgao)
                .containsExactly("UFRRJ - Universidade Federal Rural do Rio de Janeiro", "UERJ");
        PistaEncontrada ufrrj = pistas.get(0);
        assertThat(ufrrj.url()).isEqualTo(NOTICIA);
        assertThat(ufrrj.vagas()).isEqualTo("13 vagas até R$ 3.181,39");
        assertThat(ufrrj.cargos()).isEqualTo("Vários Cargos");
        assertThat(ufrrj.escolaridade()).isEqualTo("Médio / Técnico");
        assertThat(ufrrj.inscricaoAte()).isEqualTo("13/10/2026");
        assertThat(FontePci.podeTerCargoTecnico(ufrrj)).isTrue();
        assertThat(FontePci.podeTerCargoTecnico(pistas.get(1))).isFalse();
        assertThat(ufrrj.motivoDescarte()).isNull();
        assertThat(pistas.get(1).motivoDescarte()).isEqualTo("A listagem indica apenas: Superior");
    }

    @Test
    void filtroDeEscolaridadeNaDuvidaMantem() {
        assertThat(FontePci.podeTerCargoTecnico(pista("Vários Cargos", "Médio / Superior"))).isTrue();
        assertThat(FontePci.podeTerCargoTecnico(pista("Técnico de Enfermagem", "Médio"))).isTrue();
        assertThat(FontePci.podeTerCargoTecnico(pista("Professor II", "Médio / Superior"))).isFalse();
        assertThat(FontePci.podeTerCargoTecnico(pista("Agente Comunitário de Saúde", "Ensino Médio"))).isFalse();
        assertThat(FontePci.podeTerCargoTecnico(pista("Vários Cargos", null))).isTrue();
    }

    private static PistaEncontrada pista(String cargos, String escolaridade) {
        return new PistaEncontrada("PCI", NOTICIA, "t", "o", "RJ", "1 vaga", cargos, escolaridade, "01/01/2027");
    }

    // ------------------------------------------------------------------ da notícia ao edital oficial

    private static String noticia(String textoArtigo) {
        return "<html><body><article id=\"noticia\"><h1>UFRRJ publica edital</h1><p>" + textoArtigo + "</p>"
                + "<a href=\"javascript:void(0);\" class=\"edital-pdf-link\" data-code=\"x\" title=\"EDITAL Nº 53\">EDITAL Nº 53</a>"
                + "</article><a href=\"https://www.pciconcursos.com.br/apostilas/ufrrj\">apostila</a></body></html>";
    }

    private LocalizadorEditalOficial localizador(WebFalsa web) {
        return new LocalizadorEditalOficial(web, ValidadorFontes.padrao());
    }

    @Test
    void achaOEditalDeAberturaPeloNumeroESeparaRetificacao() throws IOException {
        WebFalsa web = new WebFalsa()
                .html(NOTICIA, noticia("As inscrições poderão ser feitas pelo site www.ufrrj.br/concursos. A taxa é de R$ 65,00."))
                .html("https://www.ufrrj.br/concursos", """
                        <html><body>
                          <a href="/docs/edital-52-2026-abertura.pdf">Edital nº 52/2026 – Abertura</a>
                          <a href="/docs/edital-53-2026.pdf">Edital nº 53/2026 – Abertura (técnico-administrativos)</a>
                          <a href="/docs/resultado-edital-53.pdf">Resultado final – Edital 53</a>
                          <a href="/docs/retificacao-edital-53.pdf">Retificação do Edital 53</a>
                          <a href="https://www.pciconcursos.com.br/pdf/copia-edital-53.pdf">Edital 53 (cópia)</a>
                        </body></html>""");

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.encontrado()).isTrue();
        assertThat(r.urlEdital()).isEqualTo("https://www.ufrrj.br/docs/edital-53-2026.pdf");
        assertThat(r.outrosDocumentos()).extracting(LocalizadorEditalOficial.DocumentoNaPagina::url)
                .containsExactly("https://www.ufrrj.br/docs/retificacao-edital-53.pdf");
        // nunca baixa a cópia do agregador
        assertThat(web.acessos).noneMatch(u -> u.contains("pciconcursos.com.br/pdf"));
    }

    @Test
    void segueParaAPaginaDoConcursoQuandoOSiteNaoTemOPdfDireto() throws IOException {
        WebFalsa web = new WebFalsa()
                .html(NOTICIA, noticia("Inscrições em www.ufrrj.br/concursos até 13/10/2026."))
                .html("https://www.ufrrj.br/concursos", """
                        <a href="/concursos/tae-edital-53-2026">Concurso TAE – Edital 53/2026</a>
                        <a href="/concursos/docente-edital-40-2026">Concurso docente – Edital 40/2026</a>""")
                .html("https://www.ufrrj.br/concursos/tae-edital-53-2026",
                        "<a href=\"/arquivos/edital_abertura.pdf\">Edital de abertura</a>");

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.urlEdital()).isEqualTo("https://www.ufrrj.br/arquivos/edital_abertura.pdf");
        assertThat(web.acessos).doesNotContain("https://www.ufrrj.br/concursos/docente-edital-40-2026");
    }

    @Test
    void noticiaSemSiteOficial() throws IOException {
        WebFalsa web = new WebFalsa().html(NOTICIA,
                noticia("Mais detalhes em www.jcconcursos.com.br e no nosso canal www.pciconcursos.com.br."));

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.SEM_FONTE_OFICIAL);
    }

    @Test
    void certificadoInvalidoViraErroComMotivo() throws IOException {
        WebFalsa web = new WebFalsa()
                .html(NOTICIA, noticia("Inscrições pelo site www.ufrrj.br/concursos."))
                .erro("https://www.ufrrj.br/concursos", new ClienteWebHttp.CertificadoInvalido("https://www.ufrrj.br/concursos", null));

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.ERRO_ACESSO);
        assertThat(r.motivo()).contains("Certificado de segurança inválido");
    }

    @Test
    void bancaForaDaListaEncontraMasPedeConfirmacao() throws IOException {
        // Caso real (Queimados/IAN): a notícia liga para a prefeitura (sem o PDF) e para a banca, que não está cadastrada.
        String noticia = "https://www.pciconcursos.com.br/noticias/prefeitura-de-cidade-nova-abre-concurso";
        WebFalsa web = new WebFalsa()
                .html(noticia, "<article id=\"noticia\"><p>Inscrições pelo site do "
                        + "<a href=\"https://www.bancanova.org.br/\">Instituto Banca Nova</a>. Veja também a "
                        + "<a href=\"https://cidadenova.rj.gov.br/\">Prefeitura Municipal de Cidade Nova</a>.</p></article>")
                .html("https://cidadenova.rj.gov.br/", "<a href=\"/noticias/obras\">Obras na praça</a>")
                .html("https://www.bancanova.org.br/", """
                        <a href="/concursos/prefeitura-de-cidade-nova-2026">Prefeitura de Cidade Nova 2026</a>
                        <a href="/concursos/prefeitura-de-outra-cidade-2026">Prefeitura de Outra Cidade 2026</a>""")
                .html("https://www.bancanova.org.br/concursos/prefeitura-de-cidade-nova-2026",
                        "<a href=\"/arquivos/edital-abertura-cidade-nova.pdf\">Edital de Abertura</a>");

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(noticia, "Prefeitura de Cidade Nova");

        assertThat(r.encontrado()).isTrue();
        assertThat(r.urlEdital()).isEqualTo("https://www.bancanova.org.br/arquivos/edital-abertura-cidade-nova.pdf");
        assertThat(r.fonteConfirmada()).isFalse();
        assertThat(r.motivo()).contains("www.bancanova.org.br").contains("confirme a fonte");
        assertThat(web.acessos).doesNotContain("https://www.bancanova.org.br/concursos/prefeitura-de-outra-cidade-2026");
    }

    @Test
    void enderecosPontoRioEDominiosDeBancasReais() throws IOException {
        WebFalsa web = new WebFalsa()
                .html(NOTICIA, noticia("As inscrições poderão ser realizadas pelo site riosaude.prefeitura.rio até 30/09."))
                .html("https://riosaude.prefeitura.rio", "<a href=\"/edital-01-2026.pdf\">Edital 01/2026 – Abertura</a>");

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.urlEdital()).isEqualTo("https://riosaude.prefeitura.rio/edital-01-2026.pdf");
        assertThat(r.fonteConfirmada()).isTrue();
        assertThat(LocalizadorEditalOficial.palavrasDoOrgao("Prefeitura de Queimados")).containsExactly("queimados");
    }

    @Test
    void siteOficialSemEditalClaro() throws IOException {
        WebFalsa web = new WebFalsa()
                .html(NOTICIA, noticia("Inscrições pelo site www.ufrrj.br/concursos."))
                .html("https://www.ufrrj.br/concursos", "<a href=\"/docs/gabarito-53.pdf\">Gabarito – Edital 53</a>");

        LocalizadorEditalOficial.Resultado r = localizador(web).localizar(NOTICIA);

        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.EDITAL_NAO_ENCONTRADO);
        assertThat(r.paginaOficial()).isEqualTo("https://www.ufrrj.br/concursos");
    }
}
