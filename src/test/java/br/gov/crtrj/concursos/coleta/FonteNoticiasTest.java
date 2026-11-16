package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FonteNoticiasTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-13T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final RegiaoRj RJ = new RegiaoRj(List.of("Colégio Pedro II"));

    private static final String FOLHA_RJ = "https://folha.qconcursos.com/concursos-rio-de-janeiro-rj/feed";
    private static final String FOLHA_ABERTOS = "https://folha.qconcursos.com/concursos-abertos/feed";
    private static final String CNB_RJ = "https://concursosnobrasil.com/concursos/rj/feed/";
    private static final String N = "https://folha.qconcursos.com/n/";

    /** Item no formato do feed da Folha Dirigida (título e resumo em CDATA). */
    private static String item(String slug, String titulo, String resumo, String data) {
        return "<item><title><![CDATA[" + titulo + "]]></title><description><![CDATA[" + resumo + "]]></description>"
                + "<pubDate>" + data + "</pubDate><link>" + N + slug + "</link><guid>" + N + slug + "</guid></item>";
    }

    private static String rss(String... itens) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel><title>Feed</title>"
                + "<link>https://folha.qconcursos.com/</link>" + String.join("", itens) + "</channel></rss>";
    }

    private static WebFalsa internet() {
        return new WebFalsa()
                .html(FOLHA_RJ, rss(
                        item("concurso-cbmerj-smtv-2026-edital-tenente", "CBMERJ divulga 1º edital para o SMTV com 192 vagas; veja áreas!",
                                "Edital do Serviço Militar Temporário Voluntário tem vagas de nível superior.", "Fri, 02 Oct 2026 08:42:23 -0300"),
                        item("concurso-pmerj-cfo-2026-banca-contratada", "Concurso PMERJ: banca é contratada e edital para o CFO pode sair",
                                "Contrato com a banca foi assinado.", "Thu, 08 Oct 2026 08:30:05 -0300"),
                        item("concurso-guarda-de-sao-goncalo-rj-inscricoes", "Concurso São Gonçalo RJ: baixe edital verticalizado grátis",
                                "Material de estudo para o concurso.", "Mon, 28 Sep 2026 16:51:37 -0300"),
                        item("concurso-pc-rj-regras-de-promocao-atualizadas", "Concurso PC RJ: governo atualiza regras de promoção de policiais",
                                "Decreto muda critérios de promoção na carreira.", "Wed, 30 Sep 2026 10:19:16 -0300"),
                        item("estagio-smpda-rj", "Estágio SMPDA RJ abre 100 vagas em edital de processo seletivo",
                                "Seleção de estudantes.", "Fri, 11 Oct 2026 11:16:19 -0300"),
                        item("concurso-itaguai-2026", "Prefeitura de Itaguaí abre concurso com 300 vagas",
                                "Inscrições abertas.", "Sat, 01 Aug 2026 10:00:00 -0300")))
                .html(FOLHA_ABERTOS, rss(
                        item("concurso-cbmerj-smtv-2026-edital-tenente", "CBMERJ divulga 1º edital para o SMTV com 192 vagas; veja áreas!",
                                "Repetido no outro feed.", "Fri, 02 Oct 2026 08:42:23 -0300"),
                        item("prefeitura-de-sobral-ce-abre-concurso", "Prefeitura de Sobral CE abre concurso para professores temporários",
                                "Inscrições até 30 de outubro.", "Tue, 13 Oct 2026 09:00:00 -0300"),
                        item("prefeitura-de-rio-bonito-rj-edital", "Prefeitura de Rio Bonito (RJ) lança edital de seletivo da Educação",
                                "Inscrições começam na segunda.", "Tue, 13 Oct 2026 10:00:00 -0300")))
                .html(CNB_RJ, """
                        <?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Rio de Janeiro &#8211; Concursos no Brasil</title>
                        <item><title>RJ: Prefeitura de Tanguá abre 65 vagas em edital de seletivo</title>
                          <link>https://concursosnobrasil.com/concursos/rj/2026/10/08/rj-prefeitura-de-tangua-abre-65-vagas/#more</link>
                          <pubDate>Thu, 08 Oct 2026 12:44:20 +0000</pubDate>
                          <description><![CDATA[<p>Processo seletivo da Prefeitura de Tanguá tem oportunidades na Educação.</p>
                        <p>O post <a href="https://concursosnobrasil.com/x/">RJ: Prefeitura de Tanguá abre 65 vagas</a> apareceu primeiro em <a href="https://concursosnobrasil.com">Concursos no Brasil</a>.</p>]]></description>
                        </item></channel></rss>""");
    }

    private static FonteNoticias fonte(WebFalsa web, FonteNoticias.Portal... portais) {
        return new FonteNoticias(web, List.of(portais), RJ, RELOGIO);
    }

    @Test
    void ficamAsNoticiasDoRjComEditalAbertoEAsDemaisTemMotivo() {
        FonteNoticias fonte = fonte(internet(),
                new FonteNoticias.Portal("Folha Dirigida", List.of(FOLHA_ABERTOS), List.of(FOLHA_RJ)),
                new FonteNoticias.Portal("Concursos no Brasil", List.of(), List.of(CNB_RJ)));
        List<String> avisos = new ArrayList<>();

        Map<String, PistaEncontrada> pistas = fonte.listar(url -> false, avisos).stream()
                .collect(Collectors.toMap(PistaEncontrada::url, Function.identity()));

        assertThat(avisos).isEmpty();
        // a mesma notícia em dois feeds vira uma pista só
        assertThat(pistas).hasSize(9);

        PistaEncontrada cbmerj = pistas.get(N + "concurso-cbmerj-smtv-2026-edital-tenente");
        assertThat(cbmerj.motivoDescarte()).isNull();
        assertThat(cbmerj.fonte()).isEqualTo("Folha Dirigida");
        assertThat(cbmerj.orgao()).isEqualTo("CBMERJ");
        assertThat(cbmerj.uf()).isEqualTo("RJ");
        assertThat(cbmerj.vagas()).isEqualTo("192 vagas");

        assertThat(pistas.get(N + "concurso-pmerj-cfo-2026-banca-contratada").motivoDescarte()).contains("etapa sem edital");
        assertThat(pistas.get(N + "concurso-guarda-de-sao-goncalo-rj-inscricoes").motivoDescarte()).contains("etapa sem edital");
        assertThat(pistas.get(N + "concurso-pc-rj-regras-de-promocao-atualizadas").motivoDescarte())
                .isEqualTo("A notícia não fala de edital publicado nem de inscrições.");
        assertThat(pistas.get(N + "estagio-smpda-rj").motivoDescarte()).contains("estágio");
        assertThat(pistas.get(N + "concurso-itaguai-2026").motivoDescarte()).isEqualTo("Notícia de 01/08/2026, com mais de 30 dias.");

        // feed do país todo: fica o que cita o RJ
        assertThat(pistas.get(N + "prefeitura-de-sobral-ce-abre-concurso").motivoDescarte()).isEqualTo("Fora do RJ, segundo a notícia.");
        PistaEncontrada rioBonito = pistas.get(N + "prefeitura-de-rio-bonito-rj-edital");
        assertThat(rioBonito.motivoDescarte()).isNull();
        assertThat(rioBonito.orgao()).isEqualTo("Prefeitura de Rio Bonito");

        // WordPress: link sem "#more" e resumo sem o rodapé "O post ... apareceu primeiro em"
        PistaEncontrada tangua = pistas.get("https://concursosnobrasil.com/concursos/rj/2026/10/08/rj-prefeitura-de-tangua-abre-65-vagas/");
        assertThat(tangua.motivoDescarte()).isNull();
        assertThat(tangua.fonte()).isEqualTo("Concursos no Brasil");
        assertThat(tangua.orgao()).isEqualTo("Prefeitura de Tanguá");
        assertThat(tangua.vagas()).isEqualTo("65 vagas");
    }

    @Test
    void feedForaDoArViraAvisoSemDerrubarOsOutros() {
        FonteNoticias fonte = fonte(internet(),
                new FonteNoticias.Portal("JC Concursos", List.of("https://jcconcursos.com.br/feed"), List.of()),
                new FonteNoticias.Portal("Concursos no Brasil", List.of(), List.of(CNB_RJ)));
        List<String> avisos = new ArrayList<>();

        List<PistaEncontrada> pistas = fonte.listar(url -> false, avisos);

        assertThat(avisos).containsExactly("JC Concursos: o feed respondeu HTTP 404 (https://jcconcursos.com.br/feed)");
        assertThat(pistas).hasSize(1);
    }

    @Test
    void leFeedAtom() {
        WebFalsa web = new WebFalsa().html("https://portal.exemplo/atom.xml", """
                <?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Portal</title>
                <entry><title>UFRRJ abre concurso para técnico em eletrotécnica</title>
                  <link rel="alternate" href="https://portal.exemplo/ufrrj-concurso"/>
                  <updated>2026-10-12T09:00:00-03:00</updated><summary>Edital publicado.</summary></entry></feed>""");
        FonteNoticias fonte = fonte(web, new FonteNoticias.Portal("Portal", List.of("https://portal.exemplo/atom.xml"), List.of()));

        List<PistaEncontrada> pistas = fonte.listar(url -> false, new ArrayList<>());

        assertThat(pistas).singleElement().satisfies(p -> {
            assertThat(p.url()).isEqualTo("https://portal.exemplo/ufrrj-concurso");
            assertThat(p.orgao()).isEqualTo("UFRRJ");
            assertThat(p.motivoDescarte()).isNull();   // "UFRRJ": sigla terminada em RJ
        });
    }

    @Test
    void linkDePropagandaNoTextoDaNoticiaNaoEhSeguido() throws Exception {
        String noticia = N + "concurso-cbmerj-smtv-2026-edital-tenente";
        String aocp = "https://www.institutoaocp.org.br/concursos/693";
        String propaganda = "https://cta-service-cms2.hubspot.com/web-interactives/public/v1/track/redirect?x=1";
        WebFalsa web = new WebFalsa()
                .html(noticia, "<article id=\"article-content\"><p>Edital nº 1 no site da <a href=\"" + aocp + "\">banca</a>.</p>"
                        + "<a href=\"" + propaganda + "\">Estude grátis</a></article>")
                .html(aocp, "<div id=\"app\">Carregando...</div>")   // a lista de arquivos é montada por JavaScript
                .bloquear("https://cta-service-cms2.hubspot.com/");

        LocalizadorEditalOficial.Resultado r = new LocalizadorEditalOficial(web, ValidadorFontes.padrao()).localizar(noticia, "CBMERJ");

        // sem o filtro, o robots.txt da HubSpot faria a pista parecer "site não permite robôs"
        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.EDITAL_NAO_ENCONTRADO);
        assertThat(r.paginaOficial()).isEqualTo(aocp);
        assertThat(web.acessos).containsExactly(noticia, aocp);
    }

    @Test
    void orgaoSaiDaManchete() {
        assertThat(FonteNoticias.orgaoDoTitulo("Concurso Prefeitura de São Gonçalo (RJ) anuncia a abertura de mais de 600 vagas"))
                .isEqualTo("Prefeitura de São Gonçalo");
        assertThat(FonteNoticias.orgaoDoTitulo("Processo seletivo CBMERJ abre 192 vagas com remuneração de até R$ 12,2 mil"))
                .isEqualTo("CBMERJ");
        assertThat(FonteNoticias.orgaoDoTitulo("MOBI-Rio está com mais de 80 vagas abertas em novo seletivo")).isEqualTo("MOBI-Rio");
        assertThat(FonteNoticias.orgaoDoTitulo("Concurso Seplag RJ: veja as datas e etapas após inscrição")).isEqualTo("Seplag RJ");
        assertThat(FonteNoticias.orgaoDoTitulo("Concursos RJ: veja as oportunidades da semana")).isNull();
    }

    @Test
    void siglaTerminadaEmRjContaComoRj() {
        assertThat(RJ.cita("CBMERJ divulga edital")).isTrue();
        assertThat(RJ.termoDoEstadoCitado("Edital do CREMERJ para técnico")).isEqualTo("cremerj");
        assertThat(RJ.cita("Prefeitura de Sobral CE abre concurso")).isFalse();
    }
}
