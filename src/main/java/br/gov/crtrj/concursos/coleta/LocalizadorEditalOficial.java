package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.fontes.ValidadorFontes.TipoFonte;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Da notícia (fonte de descoberta) até o PDF do edital:
 * <ol>
 *   <li>lê a notícia e extrai os sites citados (links e texto: "pelo site www.ufrrj.br/concursos");</li>
 *   <li>procura primeiro nos sites oficiais (órgão ou banca conhecidos pelo {@link ValidadorFontes});
 *       se não achar, nos demais sites citados que não sejam agregadores — o edital encontrado assim
 *       fica marcado como de fonte a confirmar;</li>
 *   <li>pontua cada PDF: "edital"/"abertura", nº do edital e nome do órgão sobem; gabarito, resultado,
 *       convocação descem. Se a página não tiver o PDF, segue links do mesmo site que citam o
 *       concurso, o nº do edital ou o órgão.</li>
 * </ol>
 * Nunca usa a cópia do PDF hospedada no agregador.
 */
public final class LocalizadorEditalOficial {

    /** O que aconteceu na busca. */
    public enum Situacao {
        ENCONTRADO,
        SEM_FONTE_OFICIAL,
        EDITAL_NAO_ENCONTRADO,
        ERRO_ACESSO,
        /** O site não permite robôs (robots.txt): o revisor precisa baixar pelo navegador. */
        BLOQUEADA
    }

    /**
     * @param fonteConfirmada o PDF está num domínio reconhecido como oficial; false = site citado na
     *                        notícia, mas fora da lista (ex.: banca nova) — precisa de confirmação
     */
    public record Resultado(Situacao situacao, String urlEdital, boolean fonteConfirmada, String paginaOficial,
                            List<DocumentoNaPagina> outrosDocumentos, String motivo) {

        public boolean encontrado() {
            return situacao == Situacao.ENCONTRADO;
        }
    }

    private static final Pattern ENDERECO = Pattern.compile(
            "(?i)\\b((?:https?://)?(?:[a-z0-9-]+\\.)+(?:gov\\.br|edu\\.br|org\\.br|com\\.br|net\\.br|br|rio)(?:/[^\\s\"'<>)\\]]*)?)");
    private static final Pattern NUMERO_EDITAL = Pattern.compile("(?i)edital\\s*n?[º°o.]*\\s*(\\d{1,4})");
    private static final Pattern POSITIVO = Pattern.compile("edital|abertura");
    private static final Pattern NEGATIVO = Pattern.compile(
            "resultado|gabarito|convoca|homolog|classifica|recurso|isenc|inscritos|local(?:is)? de prova|cartao|ata |"
                    + "prova|caderno|questoes|lista|deferid|nomeac|posse|laudo|requerimento|formulario|anexo");
    /** "Retificação", "errata"... mas não "Edital (retificado em 09/09)", que é o próprio edital consolidado. */
    private static final Pattern ALTERACAO = Pattern.compile("retifica(?!d)|errata|aditivo|comunicado|aviso|prorroga");
    /** PDFs institucionais do site (rodapé da banca): não são do concurso. */
    private static final Pattern INSTITUCIONAL = Pattern.compile(
            "privacidade|cookies|termos? de uso|lgpd|codigo de (etica|conduta)|integridade|politica de|estatuto");
    /** Palavras de tipo de órgão removidas do início do nome ("Prefeitura Municipal de ..."). */
    private static final Set<String> PREFIXOS_DE_ORGAO = Set.of("prefeitura", "municipal", "municipio", "camara",
            "governo", "estado", "secretaria", "conselho", "regional", "de", "do", "da", "dos", "das");
    /**
     * Onde fica o texto da notícia, do mais específico ao mais genérico (PCI, Folha Dirigida, JC
     * Concursos, WordPress): fora dele estão propaganda e links para outras notícias.
     */
    private static final List<String> TEXTO_DA_NOTICIA = List.of("article#noticia", "#noticia", "#article-content",
            "[itemprop=articleBody]", ".artigo_texto", ".entry-content", "article");
    /**
     * Links que aparecem no texto das notícias mas nunca levam ao edital: propaganda, rastreamento de
     * cliques e redes sociais (o "saiba mais" da HubSpot na Folha Dirigida, por exemplo).
     */
    private static final List<String> SEM_EDITAL = List.of("hubspot.com", "hubs.ly", "hs-sites.com", "doubleclick.net",
            "googlesyndication.com", "googleadservices.com", "google.com", "facebook.com", "instagram.com", "twitter.com",
            "x.com", "youtube.com", "youtu.be", "whatsapp.com", "wa.me", "t.me", "telegram.me", "linkedin.com",
            "tiktok.com", "pinterest.com", "threads.net", "apple.com", "amazon.com.br", "amazon.com");
    private static final int PONTUACAO_MINIMA = 5;
    private static final int MAX_PAGINAS_SEGUIDAS = 6;
    /** Link de PDF com menos letras que isso ("DOWNLOAD", "Clique aqui") é descrito pela linha em volta. */
    private static final int MIN_LETRAS_LINK = 15;
    private static final int MAX_TEXTO_LINHA = 300;

    private final ClienteWeb web;
    private final ValidadorFontes fontes;

    public LocalizadorEditalOficial(ClienteWeb web, ValidadorFontes fontes) {
        this.web = web;
        this.fontes = fontes;
    }

    /** A pista veio de agregador (PCI, portal de notícias): a página é uma notícia, e o edital está num site citado nela. */
    public boolean ehNoticia(String url) {
        return fontes.classificar(url) == TipoFonte.AGREGADOR;
    }

    public Resultado localizar(String urlNoticia) throws IOException {
        return localizar(urlNoticia, null);
    }

    /** @param orgao nome do órgão como a fonte de descoberta escreve (ajuda a navegar no site da banca) */
    public Resultado localizar(String urlNoticia, String orgao) throws IOException {
        ClienteWeb.Resposta noticia = web.get(urlNoticia);
        if (!noticia.ok()) {
            return new Resultado(Situacao.ERRO_ACESSO, null, false, null, List.of(),
                    "Notícia indisponível (HTTP " + noticia.status() + ").");
        }
        Document doc = Jsoup.parse(new ByteArrayInputStream(noticia.corpo()), null, noticia.urlFinal());
        Element corpo = TEXTO_DA_NOTICIA.stream().map(doc::selectFirst).filter(Objects::nonNull).findFirst()
                .orElse(doc.body());
        return buscarNosSites(sitesCitados(corpo), new Busca(numeroDoEdital(corpo), palavrasDoOrgao(orgao)), "A notícia");
    }

    /**
     * Ato de diário oficial (o texto já está em mãos): o edital é procurado nos sites que o ato
     * cita ("as inscrições serão feitas no endereço www.concursos.uff.br").
     */
    public Resultado localizarPorTexto(String texto, String orgao) {
        Element corpo = Jsoup.parse("<p></p>").body();
        corpo.text(texto == null ? "" : texto);
        return buscarNosSites(sitesCitados(corpo), new Busca(numeroDoEdital(corpo), palavrasDoOrgao(orgao)), "O ato");
    }

    /**
     * Página do concurso no site da banca ou do órgão: o edital é o melhor PDF dela. Não segue
     * outros links — numa banca eles levam a concursos de outros órgãos.
     *
     * @param titulo título do concurso como a listagem da banca mostra (dá o nº do edital)
     */
    public Resultado localizarNaPagina(String url, String titulo) throws IOException {
        ClienteWeb.Resposta pagina;
        try {
            pagina = web.get(url);
        } catch (ClienteWeb.AcessoNaoPermitido e) {
            return new Resultado(Situacao.BLOQUEADA, null, false, url, List.of(), e.getMessage());
        }
        if (!pagina.ok()) {
            return new Resultado(Situacao.ERRO_ACESSO, null, false, url, List.of(),
                    "A página do concurso respondeu HTTP " + pagina.status() + ".");
        }
        if (pagina.pdf()) {
            return new Resultado(Situacao.ENCONTRADO, pagina.urlFinal(), fontes.classificar(pagina.urlFinal()).oficial(),
                    url, List.of(), null);
        }
        Document doc = Jsoup.parse(new ByteArrayInputStream(pagina.corpo()), null, pagina.urlFinal());
        String numero = titulo == null ? null : numeroDoEdital(Jsoup.parse("<p></p>").body().text(titulo));
        Busca busca = new Busca(numero != null ? numero : numeroDoEdital(doc.body()), List.of());
        List<Candidato> candidatos = new ArrayList<>(pdfs(doc, busca));
        candidatos.sort(Comparator.comparing(Candidato::confirmado).thenComparingInt(Candidato::pontos).reversed());
        List<DocumentoNaPagina> alteracoes = posteriores(candidatos);
        Candidato melhor = candidatos.stream().filter(Candidato::serveComoEdital).findFirst().orElse(null);
        if (melhor != null) {
            return new Resultado(Situacao.ENCONTRADO, melhor.url(), melhor.confirmado(), pagina.urlFinal(), alteracoes,
                    melhor.confirmado() ? null : "Edital encontrado em " + hostDe(melhor.url())
                            + ", fora da lista de fontes oficiais: confirme a fonte.");
        }
        return new Resultado(Situacao.EDITAL_NAO_ENCONTRADO, null, false, pagina.urlFinal(), alteracoes,
                candidatos.isEmpty()
                        ? "A página do concurso não tem PDF que o robô consiga ler (o site pode carregar os arquivos "
                        + "por JavaScript). Abra a página e informe o link do edital."
                        : "Nenhum PDF da página do concurso parece ser o edital de abertura. Informe o link do edital.");
    }

    private Resultado buscarNosSites(List<String> citados, Busca busca, String origem) {
        List<String> oficiais = citados.stream().filter(e -> fontes.classificar(e).oficial()).toList();
        List<String> outros = citados.stream().filter(e -> !fontes.classificar(e).oficial()).toList();
        if (citados.isEmpty()) {
            return new Resultado(Situacao.SEM_FONTE_OFICIAL, null, false, null, List.of(),
                    origem + " não cita o site do órgão nem da banca. Informe o link do edital manualmente.");
        }

        IOException ultimoErro = null;
        List<Candidato> candidatos = new ArrayList<>();
        for (List<String> grupo : List.of(oficiais, outros)) {
            for (String endereco : grupo) {
                try {
                    candidatos.addAll(procurar(endereco, busca));
                } catch (IOException e) {
                    ultimoErro = e;
                }
            }
            if (candidatos.stream().anyMatch(Candidato::serveComoEdital)) {
                break;   // achou nos oficiais: não precisa olhar os demais sites
            }
        }

        candidatos.sort(Comparator.comparing(Candidato::confirmado).thenComparingInt(Candidato::pontos).reversed());
        List<DocumentoNaPagina> alteracoes = posteriores(candidatos);
        String paginaOficial = oficiais.isEmpty() ? citados.get(0) : oficiais.get(0);
        Candidato melhor = candidatos.stream().filter(Candidato::serveComoEdital).findFirst().orElse(null);
        if (melhor != null) {
            // a página que lista o edital é a que o acompanhamento revisita atrás de retificações
            return new Resultado(Situacao.ENCONTRADO, melhor.url(), melhor.confirmado(),
                    melhor.pagina() != null ? melhor.pagina() : paginaOficial, alteracoes,
                    melhor.confirmado() ? null : "Edital encontrado em " + hostDe(melhor.url())
                            + ", site citado na notícia mas fora da lista de fontes oficiais: confirme a fonte.");
        }
        if (ultimoErro != null && candidatos.isEmpty()) {
            Situacao s = ultimoErro instanceof ClienteWeb.AcessoNaoPermitido ? Situacao.BLOQUEADA : Situacao.ERRO_ACESSO;
            return new Resultado(s, null, false, paginaOficial, List.of(), ultimoErro.getMessage());
        }
        return new Resultado(Situacao.EDITAL_NAO_ENCONTRADO, null, false, paginaOficial, alteracoes,
                "Sites citados (" + String.join(", ", citados.stream().map(LocalizadorEditalOficial::hostDe).distinct().toList())
                        + ") verificados, mas nenhum PDF parece ser o edital de abertura"
                        + (busca.numero() == null ? "" : " nº " + busca.numero()) + ". Informe o link do edital manualmente.");
    }

    private record Busca(String numero, List<String> palavrasOrgao) {
    }

    /** @param pagina página onde o link do PDF estava (null quando o endereço citado já era o PDF) */
    /** Retificações, comunicados... já vistos junto do edital, com o texto que os descreve na página. */
    private static List<DocumentoNaPagina> posteriores(List<Candidato> candidatos) {
        Set<String> vistos = new LinkedHashSet<>();
        return candidatos.stream()
                .filter(Candidato::alteracao)
                .filter(c -> vistos.add(c.url()))
                .map(c -> new DocumentoNaPagina(c.url(), c.texto() == null || c.texto().isBlank() ? nomeArquivo(c.url()) : c.texto()))
                .toList();
    }

    /** @param texto o que a página diz do PDF (null quando o endereço citado já era o PDF) */
    private record Candidato(String url, int pontos, boolean alteracao, boolean confirmado, String pagina, String texto) {
        boolean serveComoEdital() {
            return !alteracao && pontos >= PONTUACAO_MINIMA;
        }
    }

    /** PDFs na página e, se ela não tiver edital claro, nas páginas do mesmo site ligadas ao concurso. */
    private List<Candidato> procurar(String endereco, Busca busca) throws IOException {
        ClienteWeb.Resposta pagina = web.get(endereco);
        if (!pagina.ok()) {
            throw new IOException("Site respondeu HTTP " + pagina.status() + " em " + endereco);
        }
        if (pagina.pdf()) {
            return List.of(new Candidato(pagina.urlFinal(), PONTUACAO_MINIMA, false, fontes.classificar(pagina.urlFinal()).oficial(),
                    null, null));
        }
        Document doc = Jsoup.parse(new ByteArrayInputStream(pagina.corpo()), null, pagina.urlFinal());
        List<Candidato> candidatos = new ArrayList<>(pdfs(doc, busca));
        if (candidatos.stream().noneMatch(Candidato::serveComoEdital)) {
            String host = hostDe(pagina.urlFinal());
            // Banca ou site desconhecido publica concursos de vários órgãos: o link precisa citar o órgão ou o nº.
            TipoFonte tipoSite = fontes.classificar(pagina.urlFinal());
            boolean siteDeVariosOrgaos = tipoSite == TipoFonte.BANCA || !tipoSite.oficial();
            int seguidas = 0;
            for (Element a : doc.select("a[href]")) {
                String destino = a.absUrl("href");
                String texto = Normalizador.normalizar(textoDoLink(a, destino));
                boolean mesmoSite = host != null && host.equalsIgnoreCase(hostDe(destino));
                boolean pareceConcurso = texto.contains("concurso") || texto.contains("edital") || texto.contains("processo seletivo");
                boolean citaNumero = busca.numero() != null && contemNumero(texto, busca.numero());
                boolean citaOrgao = citaOrgao(texto, busca);
                boolean semPistas = busca.numero() == null && busca.palavrasOrgao().isEmpty();
                boolean relevante = citaOrgao
                        || (pareceConcurso && (citaNumero || semPistas || (!siteDeVariosOrgaos && busca.numero() == null)));
                if (mesmoSite && relevante && !ehPdf(destino) && !destino.equals(pagina.urlFinal())
                        && seguidas < MAX_PAGINAS_SEGUIDAS) {
                    seguidas++;
                    ClienteWeb.Resposta sub = web.get(destino);
                    if (sub.ok() && !sub.pdf()) {
                        candidatos.addAll(pdfs(Jsoup.parse(new ByteArrayInputStream(sub.corpo()), null, sub.urlFinal()), busca));
                    } else if (sub.ok()) {
                        candidatos.add(new Candidato(sub.urlFinal(), PONTUACAO_MINIMA, false, fontes.classificar(sub.urlFinal()).oficial(),
                                pagina.urlFinal(), null));
                    }
                }
            }
        }
        return candidatos;
    }

    private List<Candidato> pdfs(Document doc, Busca busca) {
        String hostPagina = hostDe(doc.location());
        List<Candidato> lista = new ArrayList<>();
        for (Element a : doc.select("a[href], option[value]")) {
            // <option value="uploads/concursos\X\y.pdf"> (menus de seleção, como no CEPUERJ)
            String bruto = a.hasAttr("href") ? a.attr("href") : a.attr("value");
            String destino = resolver(doc.location(), bruto.replace('\\', '/'));
            if (destino == null || !ehPdf(destino)) {
                continue;
            }
            TipoFonte tipo = fontes.classificar(destino);
            boolean mesmoSite = hostPagina != null && hostPagina.equalsIgnoreCase(hostDe(destino));
            if (tipo == TipoFonte.AGREGADOR || (!tipo.oficial() && !mesmoSite)) {
                continue;
            }
            String texto = Normalizador.normalizar(textoDoPdf(a) + " " + nomeArquivo(destino));
            if (INSTITUCIONAL.matcher(texto).find()) {
                continue;
            }
            int pontos = 0;
            if (POSITIVO.matcher(texto).find()) {
                pontos += 5;
            }
            if (texto.contains("abertura")) {
                pontos += 3;
            }
            if (busca.numero() != null && contemNumero(texto, busca.numero())) {
                pontos += 4;
            }
            if (citaOrgao(texto, busca)) {
                pontos += 3;
            }
            if (NEGATIVO.matcher(texto).find()) {
                pontos -= 6;
            }
            lista.add(new Candidato(destino, pontos, ALTERACAO.matcher(texto).find(), tipo.oficial(), doc.location(),
                    textoCurto(textoDoPdf(a))));
        }
        return lista;
    }

    // ------------------------------------------------------------------ acompanhamento

    /** Um PDF na página oficial que parece ser retificação, errata, comunicado... do concurso. */
    public record DocumentoNaPagina(String url, String texto) {
    }

    private static final Pattern DOCUMENTO_POSTERIOR = Pattern.compile(
            "retifica|errata|aditivo|comunicado|aviso|prorroga|adia|suspens|cancela|consolidad|cronograma|republica");
    private static final Pattern NAO_ACOMPANHAR = Pattern.compile(
            "gabarito|resultado|convoca|homolog|classifica|inscritos|local(?:is)? de prova|cartao|caderno|questoes|nomeac"
                    + "|posse|laudo|requerimento|formulario|recurso");
    /** Página com poucos PDFs é tratada como página do concurso (não precisa citar nº ou órgão em cada link). */
    private static final int MAX_PDFS_PAGINA_DO_CONCURSO = 25;

    /**
     * PDFs posteriores ao edital na página oficial do concurso. Numa página com vários concursos
     * (ex.: "Concursos" da prefeitura), o link precisa citar o nº do edital ou o órgão.
     *
     * @param numeroEdital como gravado no concurso ("001/2026"); usa-se o primeiro número
     */
    public List<DocumentoNaPagina> documentosNaPagina(String pagina, String numeroEdital, String orgao) throws IOException {
        ClienteWeb.Resposta resposta = web.get(pagina);
        if (!resposta.ok()) {
            throw new IOException("Site respondeu HTTP " + resposta.status() + " em " + pagina);
        }
        if (resposta.pdf()) {
            return List.of();
        }
        Document doc = Jsoup.parse(new ByteArrayInputStream(resposta.corpo()), null, resposta.urlFinal());
        Busca busca = new Busca(primeiroNumero(numeroEdital), palavrasDoOrgao(orgao));
        String hostPagina = hostDe(doc.location());
        List<Element> links = doc.select("a[href], option[value]");
        long totalPdfs = links.stream().map(a -> a.hasAttr("href") ? a.attr("href") : a.attr("value"))
                .filter(LocalizadorEditalOficial::ehPdf).count();
        String identificacaoPagina = Normalizador.normalizar(doc.title() + " " + caminho(doc.location()));
        boolean paginaDoConcurso = totalPdfs <= MAX_PDFS_PAGINA_DO_CONCURSO
                || citaOrgao(identificacaoPagina, busca)
                || (busca.numero() != null && contemNumero(identificacaoPagina, busca.numero()));

        Set<String> vistos = new LinkedHashSet<>();
        List<DocumentoNaPagina> lista = new ArrayList<>();
        for (Element a : links) {
            String bruto = a.hasAttr("href") ? a.attr("href") : a.attr("value");
            String destino = resolver(doc.location(), bruto.replace('\\', '/'));
            if (destino == null || !ehPdf(destino) || !vistos.add(destino)) {
                continue;
            }
            TipoFonte tipo = fontes.classificar(destino);
            boolean mesmoSite = hostPagina != null && hostPagina.equalsIgnoreCase(hostDe(destino));
            if (tipo == TipoFonte.AGREGADOR || (!tipo.oficial() && !mesmoSite)) {
                continue;
            }
            String textoOriginal = textoCurto(textoDoPdf(a));
            String texto = Normalizador.normalizar(textoOriginal + " " + nomeArquivo(destino));
            boolean doConcurso = paginaDoConcurso || citaOrgao(texto, busca)
                    || (busca.numero() != null && contemNumero(texto, busca.numero()));
            if (DOCUMENTO_POSTERIOR.matcher(texto).find() && !NAO_ACOMPANHAR.matcher(texto).find()
                    && !INSTITUCIONAL.matcher(texto).find() && doConcurso) {
                lista.add(new DocumentoNaPagina(destino, textoOriginal.isBlank() ? nomeArquivo(destino) : textoOriginal));
            }
        }
        return lista;
    }

    private static String primeiroNumero(String numeroEdital) {
        if (numeroEdital == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\d+").matcher(numeroEdital);
        return m.find() ? m.group().replaceFirst("^0+(?=\\d)", "") : null;
    }

    /** Texto de link sem espaços repetidos e com tamanho limitado. */
    private static String textoCurto(String s) {
        String limpo = s.replaceAll("\\s+", " ").strip();
        return limpo.length() > 300 ? limpo.substring(0, 300) : limpo;
    }

    /** Sites citados na notícia (links e texto), sem agregadores, na ordem em que aparecem. */
    List<String> sitesCitados(Element corpo) {
        Set<String> enderecos = new LinkedHashSet<>();
        for (Element a : corpo.select("a[href]")) {
            enderecos.add(a.absUrl("href"));
        }
        Matcher m = ENDERECO.matcher(corpo.text());
        while (m.find()) {
            String e = m.group(1).replaceAll("[.,;:]+$", "");
            enderecos.add(e.toLowerCase(Locale.ROOT).startsWith("http") ? e : "https://" + e);
        }
        return enderecos.stream()
                .filter(e -> e.startsWith("http"))
                .filter(e -> hostDe(e) != null)
                .filter(e -> fontes.classificar(e) != TipoFonte.AGREGADOR)
                .filter(e -> !semEdital(hostDe(e)))
                .toList();
    }

    private static boolean semEdital(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return SEM_EDITAL.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }

    private static String numeroDoEdital(Element corpo) {
        Element link = corpo.ownerDocument().selectFirst("a.edital-pdf-link[title]");
        String fonte = link != null ? link.attr("title") : corpo.text();
        Matcher m = NUMERO_EDITAL.matcher(fonte);
        return m.find() ? m.group(1).replaceFirst("^0+(?=\\d)", "") : null;
    }

    /**
     * Termos que identificam o órgão numa página de banca: a sigla e o nome sem o tipo de órgão.
     * "Prefeitura de Queimados" → [queimados]; "CREMERJ - Conselho Regional de Medicina do RJ" →
     * [cremerj, medicina do rj]; "Prefeitura de Engenheiro Paulo de Frontin" → [engenheiro paulo de frontin].
     */
    static List<String> palavrasDoOrgao(String orgao) {
        if (orgao == null || orgao.isBlank()) {
            return List.of();
        }
        List<String> termos = new ArrayList<>();
        String[] partes = orgao.split("\\s+[-–]\\s+", 2);
        String nome = orgao;
        if (partes.length == 2) {
            String sigla = Normalizador.chave(partes[0]);
            if (sigla.length() >= 3 && !sigla.contains(" ")) {
                termos.add(sigla);
                nome = partes[1];
            }
        }
        List<String> palavras = new ArrayList<>(Arrays.asList(Normalizador.chave(nome).split(" ")));
        while (!palavras.isEmpty() && PREFIXOS_DE_ORGAO.contains(palavras.get(0))) {
            palavras.remove(0);
        }
        if (!palavras.isEmpty() && palavras.get(palavras.size() - 1).equals("rj")) {
            palavras.remove(palavras.size() - 1);
        }
        String frase = String.join(" ", palavras);
        if (frase.length() >= 4) {
            termos.add(frase);
        }
        return termos;
    }

    /** Compara a expressão inteira, com fronteira de palavra ("cidade nova" não casa com "outra cidade"). */
    private static boolean citaOrgao(String texto, Busca busca) {
        String chave = " " + Normalizador.chave(texto) + " ";
        return busca.palavrasOrgao().stream().anyMatch(t -> chave.contains(" " + t + " "));
    }

    /** Texto do link + caminho do endereço (sem o domínio, que costuma conter o nome do órgão). */
    private static String textoDoLink(Element a, String destino) {
        return a.text() + " " + a.attr("title") + " " + caminho(destino);
    }

    /**
     * Texto que descreve o PDF. Quando o link diz pouco ("DOWNLOAD", "Clique aqui", um ícone), vale
     * a linha em volta dele (o maior bloco que não tem outro PDF), onde fica o nome do documento.
     */
    private static String textoDoPdf(Element a) {
        String proprio = (a.text() + " " + a.attr("title")).strip();
        if (proprio.replaceAll("[^\\p{L}]", "").length() >= MIN_LETRAS_LINK) {
            return proprio;
        }
        Element bloco = a;
        String melhor = proprio;
        while (bloco.parent() != null && !bloco.parent().tagName().equals("body")) {
            Element pai = bloco.parent();
            long pdfs = pai.select("a[href], option[value]").stream()
                    .map(x -> x.hasAttr("href") ? x.attr("href") : x.attr("value"))
                    .filter(LocalizadorEditalOficial::ehPdf).distinct().count();
            if (pdfs > 1 || pai.text().length() > MAX_TEXTO_LINHA) {
                break;
            }
            bloco = pai;
            melhor = pai.text();
        }
        return melhor;
    }

    /** Caminho do endereço com "-" e "_" viram espaço ("/concursos/edital-53-2026" → "/concursos/edital 53 2026"). */
    private static String caminho(String url) {
        String caminho;
        try {
            caminho = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            caminho = "";
        }
        return caminho == null ? "" : caminho.replace('-', ' ').replace('_', ' ');
    }

    private static boolean contemNumero(String texto, String numero) {
        return Pattern.compile("(?<!\\d)0*" + numero + "(?!\\d)").matcher(texto).find();
    }

    private static boolean ehPdf(String url) {
        return url.toLowerCase(Locale.ROOT).contains(".pdf");
    }

    private static String nomeArquivo(String url) {
        String caminho = url.replaceAll("[?#].*$", "");
        return caminho.substring(caminho.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ');
    }

    private static String resolver(String base, String relativo) {
        try {
            return URI.create(base).resolve(relativo.strip().replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostDe(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
