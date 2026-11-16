package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FonteBancasTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final RegiaoRj RJ = new RegiaoRj(List.of("Transpetro"));

    private static final String SELECON = "https://selecon.org.br/";
    private static final String IAN = "https://portal.ian.org.br/edital";
    private static final String FGV = "https://conhecimento.fgv.br/concursos";
    private static final String CONSULPLAN = "https://www.institutoconsulplan.org.br/";

    /** Cartões no formato da página inicial do Selecon (cidade, UF e situação no texto). */
    private static String cartaoSelecon(String url, String edital, String cidade, String uf, String situacao) {
        return "<div class=\"col-md-4\"><div class=\"card\"><p>Edital: " + edital + "</p><p>Cidade: " + cidade
                + " UF: " + uf + "</p><p>Situação: " + situacao + "</p><a href=\"" + url + "\">Mais informações</a></div></div>";
    }

    private static String cartaoIan(String url, String status, String numero, String orgao) {
        return "<div class=\"col\"><div class=\"card\"><span>" + status + "</span><h5>Concurso Público - " + numero
                + "</h5><p>" + orgao + "</p><a href=\"" + url + "\">Mais detalhes</a></div></div>";
    }

    private static WebFalsa internet() {
        return new WebFalsa()
                .html(SELECON, "<html><body><div class=\"row\">"
                        + cartaoSelecon("https://selecon.selecao.net.br/informacoes/73/",
                        "EDITAL 001/2026/GM-NIVEL_II/PMSG CONCURSO PÚBLICO PREFEITURA DE SÃO GONÇALO-RJ", "São Gonçalo", "RJ", "Em andamento")
                        + cartaoSelecon("https://selecon-mt.selecao.net.br/informacoes/84/",
                        "EDITAL 02/2026/PMVG PROCESSO SELETIVO DA PREFEITURA DE VÁRZEA GRANDE - MT", "Várzea Grande", "MT", "Em andamento")
                        + cartaoSelecon("https://selecon.selecao.net.br/informacoes/72/",
                        "CEFET/INT/2026 PROCESSO SELETIVO PARA INGRESSO NOS CURSOS TÉCNICOS INTEGRADOS", "Rio de Janeiro", "RJ", "Em andamento")
                        + cartaoSelecon("https://selecon.selecao.net.br/informacoes/60/",
                        "EDITAL 01/2026 CONCURSO PÚBLICO CÂMARA DE MARICÁ", "Maricá", "RJ", "Encerrado")
                        + "</div><footer>Rua do Senado, 229 Centro – Rio de Janeiro / RJ <a href=\"/contato\">Contato</a></footer></body></html>")
                .html(IAN, "<html><body><div class=\"row\">"
                        + cartaoIan("/edital/ver/53", "EM INSCRIÇÃO", "01/2026", "Prefeitura Municipal de Queimados "
                        + "Período de Inscrição De: 09/09/2026 às 12:00 Até: 13/10/2026 às 23:59")
                        + cartaoIan("/edital/ver/46", "", "01/2024", "PREFEITURA MUNICIPAL DE MAGÉ")
                        + cartaoIan("/edital/ver/51", "", "01/2026", "Câmara Municipal de Jardim")
                        + "</div></body></html>")
                .html(FGV, """
                        <ul>
                          <li><a href="/concursos/seplagrj">Concurso Público para Secretaria de Planejamento e Gestão do Estado do Rio de Janeiro – SEPLAG/RJ</a></li>
                          <li><a href="/concursos/pgm.niteroi23">Concurso Público para a Procuradoria Geral do Município de Niterói</a></li>
                          <li><a href="/concursos/nosso-portfolio">Nosso portfólio</a></li>
                        </ul>""")
                .html(CONSULPLAN, """
                        <div class="cards">
                          <div class="c"><a href="https://www.institutoconsulplan.org.br/cremerj2026"><img src="/logo1.png"></a></div>
                          <div class="c"><a href="https://www.institutoconsulplan.org.br/cro2026">Conselho Regional de Odontologia do Rio de Janeiro - CRO-RJ</a> Concurso Público</div>
                          <a href="/Institucional">Institucional</a>
                        </div>""")
                .html("https://www.institutoconsulplan.org.br/cremerj2026",
                        "<html><head><title>CREMERJ 2026 - Conselho Regional de Medicina do Estado do Rio de Janeiro</title></head><body></body></html>");
    }

    private static FonteBancas fonte(WebFalsa web) {
        return new FonteBancas(web, List.of(
                new FonteBancas.Banca("Selecon", List.of(SELECON), Pattern.compile("selecao\\.net\\.br/informacoes/\\d+"), false),
                new FonteBancas.Banca("IAN", List.of(IAN), Pattern.compile("portal\\.ian\\.org\\.br/edital/ver/\\d+"), false),
                new FonteBancas.Banca("FGV", List.of(FGV), Pattern.compile("conhecimento\\.fgv\\.br/concursos/(?!nosso-portfolio)[^/?#]+$"), false),
                new FonteBancas.Banca("Instituto Consulplan", List.of(CONSULPLAN), Pattern.compile("institutoconsulplan\\.org\\.br/[a-z0-9-]+$"), false)),
                RJ, RELOGIO);
    }

    @Test
    void ficaComOsConcursosDoRjAindaAbertosEDizPorQueDescartouOsOutros() {
        WebFalsa web = internet();
        List<String> avisos = new ArrayList<>();

        Map<String, PistaEncontrada> pistas = fonte(web).listar(url -> false, avisos).stream()
                .collect(Collectors.toMap(PistaEncontrada::url, Function.identity()));

        assertThat(avisos).isEmpty();
        PistaEncontrada saoGoncalo = pistas.get("https://selecon.selecao.net.br/informacoes/73/");
        assertThat(saoGoncalo.motivoDescarte()).isNull();
        assertThat(saoGoncalo.fonte()).isEqualTo("Selecon");
        assertThat(saoGoncalo.uf()).isEqualTo("RJ");
        assertThat(saoGoncalo.titulo()).contains("SÃO GONÇALO-RJ").contains("Situação: Em andamento")
                .doesNotContain("Várzea Grande").doesNotContain("Rua do Senado");

        assertThat(pistas.get("https://selecon-mt.selecao.net.br/informacoes/84/").motivoDescarte()).startsWith("Fora do RJ");
        assertThat(pistas.get("https://selecon.selecao.net.br/informacoes/72/").motivoDescarte()).startsWith("Não é concurso para emprego");
        assertThat(pistas.get("https://selecon.selecao.net.br/informacoes/60/").motivoDescarte()).contains("terminou");

        PistaEncontrada queimados = pistas.get("https://portal.ian.org.br/edital/ver/53");
        assertThat(queimados.motivoDescarte()).isNull();
        assertThat(queimados.inscricaoAte()).isEqualTo("13/10/2026");
        assertThat(pistas.get("https://portal.ian.org.br/edital/ver/46").motivoDescarte()).startsWith("Concurso de 2024");
        assertThat(pistas.get("https://portal.ian.org.br/edital/ver/51").motivoDescarte()).startsWith("Fora do RJ");

        assertThat(pistas.get("https://conhecimento.fgv.br/concursos/seplagrj").motivoDescarte()).isNull();
        // ano no fim do endereço ("pgm.niteroi23")
        assertThat(pistas.get("https://conhecimento.fgv.br/concursos/pgm.niteroi23").motivoDescarte()).startsWith("Concurso de 2023");
        assertThat(pistas).doesNotContainKey("https://conhecimento.fgv.br/concursos/nosso-portfolio");

        // cartão só com imagem: o título vem da página do concurso
        assertThat(pistas.get("https://www.institutoconsulplan.org.br/cremerj2026").titulo()).contains("CREMERJ 2026");
        assertThat(pistas.get("https://www.institutoconsulplan.org.br/cremerj2026").motivoDescarte()).isNull();
        assertThat(pistas.get("https://www.institutoconsulplan.org.br/cro2026").motivoDescarte()).isNull();
        assertThat(pistas).doesNotContainKey("https://www.institutoconsulplan.org.br/Institucional");
    }

    @Test
    void naoAbreDeNovoAPaginaDeCartaoSemTextoJaConhecido() {
        WebFalsa web = internet();

        fonte(web).listar(url -> url.endsWith("cremerj2026"), new ArrayList<>());

        assertThat(web.acessos).doesNotContain("https://www.institutoconsulplan.org.br/cremerj2026");
    }

    @Test
    void bancaForaDoArViraAvisoSemDerrubarAsOutras() {
        WebFalsa web = internet().erro(IAN, new IOException("tempo esgotado"));
        List<String> avisos = new ArrayList<>();

        List<PistaEncontrada> pistas = fonte(web).listar(url -> false, avisos);

        assertThat(avisos).singleElement().asString().contains("IAN").contains("tempo esgotado");
        assertThat(pistas).extracting(PistaEncontrada::url).contains("https://selecon.selecao.net.br/informacoes/73/");
    }

    // ------------------------------------------------------------------ página do concurso na banca

    private static final String PAGINA_IAN = "https://portal.ian.org.br/edital/ver/53";

    /** Lista de arquivos no formato do portal do IAN: o link diz só "DOWNLOAD", o nome fica na linha. */
    private static String arquivoIan(String data, String nome, String arquivo) {
        return "<div class=\"list-group-item\"><div class=\"row\"><div class=\"col\"><small>" + data + "</small></div>"
                + "<div class=\"col\"><p>" + nome + "</p></div><div class=\"col\"><a href=\"https://ian.org.br/arquivo.php?file=edital/1/53/"
                + arquivo + ".pdf\">DOWNLOAD</a></div></div></div>";
    }

    @Test
    void achaOEditalPelaLinhaQuandoOLinkDizSoDownload() throws IOException {
        WebFalsa web = new WebFalsa().html(PAGINA_IAN, "<html><body><a href=\"https://portal.ian.org.br/edital\">Concursos e seletivos</a>"
                + "<div class=\"list-group\">"
                + arquivoIan("22/09/2026", "LISTAGEM DEFINITIVA DE CANDIDATOS ISENTOS", "aaa")
                + arquivoIan("18/09/2026", "COMUNICADO OFICIAL 01", "bbb")
                + arquivoIan("08/09/2026", "EDITAL DE ABERTURA Nº 01/2026", "ccc")
                + arquivoIan("10/09/2026", "RETIFICAÇÃO I DO EDITAL Nº 01/2026", "ddd")
                + "</div></body></html>");

        LocalizadorEditalOficial.Resultado r = new LocalizadorEditalOficial(web, ValidadorFontes.padrao())
                .localizarNaPagina(PAGINA_IAN, "Concurso Público - 01/2026 Prefeitura Municipal de Queimados");

        assertThat(r.encontrado()).isTrue();
        assertThat(r.urlEdital()).isEqualTo("https://ian.org.br/arquivo.php?file=edital/1/53/ccc.pdf");
        assertThat(r.fonteConfirmada()).isTrue();
        assertThat(r.paginaOficial()).isEqualTo(PAGINA_IAN);
        assertThat(r.outrosDocumentos()).extracting(LocalizadorEditalOficial.DocumentoNaPagina::url).containsExactlyInAnyOrder(
                "https://ian.org.br/arquivo.php?file=edital/1/53/bbb.pdf", "https://ian.org.br/arquivo.php?file=edital/1/53/ddd.pdf");
        // o nome do documento vem da linha da página, não do "arquivo.php" do endereço
        assertThat(r.outrosDocumentos()).extracting(LocalizadorEditalOficial.DocumentoNaPagina::texto)
                .anyMatch(t -> t.contains("COMUNICADO OFICIAL 01"));
        // não segue links da banca: levariam a concursos de outros órgãos
        assertThat(web.acessos).containsExactly(PAGINA_IAN);
    }

    @Test
    void editalRetificadoEOProprioEditalNaoUmaRetificacao() throws IOException {
        // página real da FGV (SEPLAG/RJ): o edital consolidado vem como "Edital (retificado em ...)"
        String pagina = "https://conhecimento.fgv.br/concursos/seplagrj";
        WebFalsa web = new WebFalsa().html(pagina, """
                <ul>
                  <li><a href="/sites/default/files/concursos/seplag_isencao-preliminar_deferidos.pdf">Resultado preliminar da análise dos pedidos de isenção da taxa de inscrição (candidatos deferidos)</a></li>
                  <li><a href="/sites/default/files/concursos/3a-retificacao-seplag-rj_0.pdf">3ª Retificação</a></li>
                  <li><a href="/sites/default/files/concursos/1a-retificacao-seplag-rj.pdf">1ª Retificação</a></li>
                  <li><a href="/sites/default/files/concursos/edital-concurso-seplag-09.09.2026.pdf">Edital (retificado em 09/09/2026)</a></li>
                  <li><a href="/sites/default/files/2026.01.16-termos-de-uso-fgv-conhecimento.pdf">Termos de Uso</a></li>
                  <li><a href="/sites/default/files/2026.06.24-aviso-de-privacidade-fgv-conhecimento.pdf">Aviso de Privacidade</a></li>
                </ul>""");

        LocalizadorEditalOficial.Resultado r = new LocalizadorEditalOficial(web, ValidadorFontes.padrao())
                .localizarNaPagina(pagina, "Concurso Público para Secretaria de Planejamento e Gestão do Estado do Rio de Janeiro");

        assertThat(r.urlEdital()).isEqualTo("https://conhecimento.fgv.br/sites/default/files/concursos/edital-concurso-seplag-09.09.2026.pdf");
        assertThat(r.outrosDocumentos()).extracting(LocalizadorEditalOficial.DocumentoNaPagina::texto)
                .containsExactly("3ª Retificação", "1ª Retificação");
    }

    @Test
    void paginaSemPdfLegivelPedeOLinkAoRevisor() throws IOException {
        WebFalsa web = new WebFalsa().html("https://www.cesgranrio.org.br/concurso/transpetro-2026/",
                "<html><body><div id=\"app\"></div><a href=\"#saiba-mais\">SAIBA MAIS</a></body></html>");

        LocalizadorEditalOficial.Resultado r = new LocalizadorEditalOficial(web, ValidadorFontes.padrao())
                .localizarNaPagina("https://www.cesgranrio.org.br/concurso/transpetro-2026/", "Transpetro 2026");

        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.EDITAL_NAO_ENCONTRADO);
        assertThat(r.motivo()).contains("JavaScript");
    }

    @Test
    void paginaProibidaARobosFicaBloqueada() throws IOException {
        WebFalsa web = new WebFalsa().bloquear("https://www.cepuerj.uerj.br/uploads");

        LocalizadorEditalOficial.Resultado r = new LocalizadorEditalOficial(web, ValidadorFontes.padrao())
                .localizarNaPagina("https://www.cepuerj.uerj.br/uploads/edital.pdf", null);

        assertThat(r.situacao()).isEqualTo(LocalizadorEditalOficial.Situacao.BLOQUEADA);
        assertThat(r.motivo()).contains("robots.txt");
        assertThat(web.acessos).isEmpty();
    }
}
