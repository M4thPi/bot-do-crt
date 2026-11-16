package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.coleta.dou.FonteDou;
import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoEncontradoRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.PistaLinha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Da página da banca, do ato do Diário Oficial e da notícia de portal até o edital analisado (e o bloqueio por robots.txt). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-bancas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-bancas",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=chave-de-teste-com-mais-de-24-caracteres",
        "concursos.coleta.listagens-pci=",
        "concursos.coleta.bancas[0].nome=IAN",
        "concursos.coleta.bancas[0].listagens[0]=https://portal.ian.org.br/edital",
        "concursos.coleta.bancas[0].link=portal.ian.org.br/edital/ver/[0-9]+",
        "concursos.coleta.bancas[1].nome=Selecon",
        "concursos.coleta.bancas[1].listagens[0]=https://selecon.org.br/",
        "concursos.coleta.bancas[1].link=selecao.net.br/informacoes/[0-9]+",
        "concursos.coleta.portais-de-noticias[0].nome=Folha Dirigida",
        "concursos.coleta.portais-de-noticias[0].feeds-rj[0]=https://folha.qconcursos.com/concursos-rio-de-janeiro-rj/feed"})
class ColetaBancasEDiarioTest {

    private static final String PAGINA_QUEIMADOS = "https://portal.ian.org.br/edital/ver/53";
    private static final String EDITAL_QUEIMADOS = "https://ian.org.br/arquivo.php?file=edital/1/53/ccc.pdf";
    private static final String PAGINA_EMGEPRON = "https://selecon.selecao.net.br/informacoes/65/";
    private static final String EDITAL_EMGEPRON = "https://anexos.cdn.selecao.net.br/uploads/746/concursos/65/anexos/edital.pdf";
    private static final String EDITAL_UFF = "https://www.coseac.uff.br/concursos/2026/edital-96-2026-abertura.pdf";
    private static final String NOTICIA_MARICA = "https://folha.qconcursos.com/n/concurso-prefeitura-marica-rj-edital";
    private static final String NOTICIA_PMERJ = "https://folha.qconcursos.com/n/concurso-pmerj-banca-contratada";
    private static final String EDITAL_MARICA = "https://www.marica.rj.gov.br/wp-content/uploads/2026/10/edital-01-2026-abertura.pdf";
    private static final String OUTRA_PREFEITURA = "https://www.niteroi.rj.gov.br/concursos";

    @TestConfiguration
    static class InternetFalsa {
        @Bean
        @Primary
        ClienteWeb webFalsa() {
            return new WebFalsa()
                    .html("https://portal.ian.org.br/edital", """
                            <div class="row"><div class="col"><div class="card"><span>EM INSCRIÇÃO</span>
                              <h5>Concurso Público - 01/2026</h5><p>Prefeitura Municipal de Queimados</p>
                              <a href="/edital/ver/53">Inscrições Abertas</a></div></div></div>""")
                    .html(PAGINA_QUEIMADOS, """
                            <div class="list-group">
                              <div class="item"><p>COMUNICADO OFICIAL 01</p><a href="https://ian.org.br/arquivo.php?file=edital/1/53/bbb.pdf">DOWNLOAD</a></div>
                              <div class="item"><p>EDITAL DE ABERTURA Nº 01/2026</p><a href="https://ian.org.br/arquivo.php?file=edital/1/53/ccc.pdf">DOWNLOAD</a></div>
                            </div>""")
                    .html("https://selecon.org.br/", """
                            <div class="row"><div class="col"><div class="card"><p>Edital: EDITAL Nº 01/2026/EMGEPRON CONCURSO PÚBLICO -
                              EMPRESA GERENCIAL DE PROJETOS NAVAIS - Níveis Médio, Médio Técnico e Superior</p>
                              <p>Cidade: Rio de Janeiro UF: RJ</p><p>Situação: Inscrições abertas</p>
                              <a href="https://selecon.selecao.net.br/informacoes/65/">Mais informações</a></div></div></div>""")
                    .html(PAGINA_EMGEPRON, "<a href=\"" + EDITAL_EMGEPRON + "\">Clique aqui e acesse o Edital de Abertura do Concurso</a>")
                    .bloquear("https://anexos.cdn.selecao.net.br/")
                    .html("https://www.coseac.uff.br/concursos",
                            "<a href=\"/concursos/2026/edital-96-2026-abertura.pdf\">Edital nº 96/2026 – Abertura</a>")
                    // portal de notícias: feed → notícia → site oficial citado no texto → edital
                    .html("https://folha.qconcursos.com/concursos-rio-de-janeiro-rj/feed", """
                            <?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Rio de Janeiro</title>
                            <item><title><![CDATA[Concurso Prefeitura de Maricá RJ abre 40 vagas para técnicos]]></title>
                              <description><![CDATA[Edital nº 01/2026 tem vagas de nível técnico.]]></description>
                              <link>%s</link></item>
                            <item><title><![CDATA[Concurso PMERJ: banca é contratada e edital pode sair]]></title>
                              <description><![CDATA[Contrato assinado.]]></description>
                              <link>%s</link></item>
                            </channel></rss>""".formatted(NOTICIA_MARICA, NOTICIA_PMERJ))
                    .html(NOTICIA_MARICA, """
                            <html><body><header><a href="/">Folha Dirigida</a></header>
                            <article id="article-content"><h1>Concurso Prefeitura de Maricá RJ abre 40 vagas</h1>
                              <p>O Edital nº 01/2026 está no <a href="https://www.marica.rj.gov.br/concursos">site da prefeitura</a>.</p>
                            </article>
                            <aside><a href="%s">Concurso Niterói: veja também</a></aside></body></html>""".formatted(OUTRA_PREFEITURA))
                    .html("https://www.marica.rj.gov.br/concursos",
                            "<a href=\"/wp-content/uploads/2026/10/edital-01-2026-abertura.pdf\">Edital nº 01/2026 – Abertura</a>");
        }
    }

    @Autowired
    ClienteWeb web;

    @MockitoBean
    BaixadorDocumentos baixador;

    @Autowired
    ServicoColeta coleta;

    @Autowired
    PistaRepositorio pistas;

    @Autowired
    ConcursoRepositorio concursos;

    @Autowired
    DocumentoEncontradoRepositorio encontrados;

    @Test
    void daBancaEDoDiarioAteOEdital() throws Exception {
        Map<String, byte[]> pdfs = Map.of(EDITAL_QUEIMADOS, EditalSintetico.prefeitura(), EDITAL_UFF, EditalSintetico.tabela(),
                EDITAL_MARICA, EditalSintetico.prefeitura(s -> s.replace("NITERÓI", "MARICÁ").replace("Niterói", "Maricá")));
        when(baixador.baixar(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (!pdfs.containsKey(url)) {
                throw new IOException("URL inesperada: " + url);
            }
            return new BaixadorDocumentos.Download(pdfs.get(url), url, "application/pdf");
        });
        // ato do DOU já registrado como pista (o INLABS não é acessado no teste)
        LocalDateTime agora = LocalDateTime.of(2026, 10, 1, 8, 0);
        pistas.inserir(new PistaLinha(null, FonteDou.NOME, "http://pesquisa.in.gov.br/x#materia-41000001",
                "EDITAL Nº 96 — Universidade Federal Fluminense", "Universidade Federal Fluminense", "RJ", null, null, null,
                null, "NOVA", null, null, null, null, 0, agora, agora,
                "Estarão abertas as inscrições do Concurso Público (Edital nº 96/2026) para Técnico em Eletrotécnica. "
                        + "Inscrições no endereço www.coseac.uff.br/concursos."));

        ServicoColeta.ResumoExecucao r = coleta.executar().orElseThrow();

        assertThat(r.erros()).as(String.join("\n", r.eventos())).isZero();
        assertThat(r.editaisAnalisados()).isEqualTo(3);

        // notícia de portal: o edital vem do site oficial citado no texto; links fora do texto não são seguidos
        PistaLinha marica = pistas.porUrl(NOTICIA_MARICA).orElseThrow();
        assertThat(marica.fonte()).isEqualTo("Folha Dirigida");
        assertThat(marica.orgao()).isEqualTo("Prefeitura de Maricá RJ");
        assertThat(marica.status()).isEqualTo("ANALISADA");
        assertThat(marica.urlOficial()).isEqualTo(EDITAL_MARICA);
        assertThat(((WebFalsa) web).acessos).doesNotContain(OUTRA_PREFEITURA);
        PistaLinha pmerj = pistas.porUrl(NOTICIA_PMERJ).orElseThrow();
        assertThat(pmerj.status()).isEqualTo("DESCARTADA");
        assertThat(pmerj.motivo()).contains("etapa sem edital");

        PistaLinha queimados = pistas.porUrl(PAGINA_QUEIMADOS).orElseThrow();
        assertThat(queimados.fonte()).isEqualTo("IAN");
        assertThat(queimados.status()).isEqualTo("ANALISADA");
        assertThat(queimados.urlOficial()).isEqualTo(EDITAL_QUEIMADOS);
        // a página do concurso na banca é a que o acompanhamento revisita; o comunicado já fica na fila
        assertThat(concursos.acompanhamento(queimados.concursoId()).orElseThrow().paginaOficial()).isEqualTo(PAGINA_QUEIMADOS);
        assertThat(encontrados.listarPorConcurso(queimados.concursoId()))
                .extracting(DocumentoEncontradoRepositorio.DocumentoEncontradoLinha::url)
                .containsExactly("https://ian.org.br/arquivo.php?file=edital/1/53/bbb.pdf");

        // o edital está num site que proíbe robôs: fica o link para o revisor baixar pelo navegador
        PistaLinha emgepron = pistas.porUrl(PAGINA_EMGEPRON).orElseThrow();
        assertThat(emgepron.status()).isEqualTo("BLOQUEADA");
        assertThat(emgepron.urlOficial()).isEqualTo(EDITAL_EMGEPRON);
        assertThat(emgepron.motivo()).contains("robots.txt");

        PistaLinha uff = pistas.porUrl("http://pesquisa.in.gov.br/x#materia-41000001").orElseThrow();
        assertThat(uff.status()).isEqualTo("ANALISADA");
        assertThat(uff.urlOficial()).isEqualTo(EDITAL_UFF);

        // pista bloqueada não é tentada de novo
        coleta.executar();
        assertThat(pistas.porUrl(PAGINA_EMGEPRON).orElseThrow().tentativas()).isEqualTo(1);
    }
}
