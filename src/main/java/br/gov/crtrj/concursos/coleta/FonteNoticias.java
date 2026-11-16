package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descoberta nos portais de notícias de concursos (Folha Dirigida, Concursos no Brasil, JC
 * Concursos...) pelo feed RSS de cada um — o caminho que o próprio site oferece para ser lido por
 * programas. Cada item do feed é uma notícia: título, resumo e link. Ficam as notícias do RJ que
 * falam de edital publicado ou de inscrições; as demais viram pista descartada, com o motivo.
 *
 * <p>Como o PCI, portal de notícias é só descoberta: o edital é procurado nos sites oficiais que a
 * notícia cita, nunca numa cópia hospedada no portal.
 */
public final class FonteNoticias implements FonteDescoberta {

    /**
     * @param feeds   feeds com notícias do país todo: fica só o que cita o RJ
     * @param feedsRj feeds que o próprio portal separa para o RJ: não precisam citar o estado
     */
    public record Portal(String nome, List<String> feeds, List<String> feedsRj) {
    }

    public static final String NOME = "Portais de notícias";

    private static final Pattern NAO_E_EMPREGO = Pattern.compile(
            "vestibular|residencia|ingresso nos cursos|cursos tecnicos|educacao profissional tecnica|monografia|estagi");
    /** Título de etapa sem edital aberto: antes (previsão, banca, autorização) ou depois (gabarito, resultado...). */
    private static final Pattern ETAPA_SEM_EDITAL = Pattern.compile(
            "banca\\s+(?:organizadora\\s+)?(?:e\\s+|foi\\s+|ja\\s+|esta\\s+)?(?:contratada|definida|escolhida)"
                    + "|contrat\\w*\\s+(?:da|de)\\s+banca|comissao|previst|\\bpreve\\b|prepar"
                    + "|\\bdeve\\s+(?:ter|sair|abrir|publicar|ser)\\b|\\bpode\\s+(?:ter|sair|abrir)\\b"
                    + "|\\btera\\s+(?:novo\\s+)?(?:edital|concurso)|\\bvai\\s+(?:ter|abrir|lancar|publicar)\\b"
                    + "|autoriza|solicita|\\bpede\\b|pedido|propost|promete"
                    + "|gabarito|resultado|nota de corte|locais? de prova|\\binscritos|plano de estudo|verticalizado"
                    + "|aprovad|convoca|homolog|\\bposse\\b|nomea|classifica|\\brecurso\\b|e-?book|apostila|simulado"
                    + "|como estudar|\\bdicas?\\b");
    /** A notícia fala de edital na rua: edital, inscrições, abertura, vagas... */
    private static final Pattern EDITAL_ABERTO = Pattern.compile(
            "edital|inscri|\\babrem?\\b|\\babert|\\blanca|\\bpublica\\s+(?:o\\s+|novo\\s+)?(?:edital|concurso|processo|selecao|seletivo)"
                    + "|\\bdivulga|retifica|prorroga|\\breabre|\\boferece|\\bpromove|\\bremunera|\\d\\s+(?:novas\\s+)?vagas");
    private static final Pattern VAGAS = Pattern.compile("(?i)(\\d[\\d.]*)\\s+(?:novas\\s+)?vagas");
    /** "RJ: Prefeitura de Tanguá abre..." — o estado antes do nome. */
    private static final Pattern ESTADO_NO_INICIO = Pattern.compile("(?i)^(?:rj|rio de janeiro)\\s*[:–-]\\s*");
    private static final Pattern TIPO_NO_INICIO = Pattern.compile(
            "(?i)^(?:concursos?|processos?\\s+seletivos?|seletivos?|sele[çc][ãa]o)(?:\\s+p[úu]blicos?)?\\s+(?:d[aoe]s?\\s+)?");
    /** Onde termina o nome do órgão no título: pontuação ou o verbo da manchete. */
    private static final Pattern FIM_DO_ORGAO = Pattern.compile(
            "(?i)\\s*[:;!?,]|\\s[–—-]\\s|\\s(?:abre|abrem|publica|publicam|divulga|lança|lanca|tem|terá|tera|recebe|"
                    + "oferece|anuncia|está|esta|prorroga|retifica|reabre|confirma|libera|promove|remunera|deve|pode|"
                    + "prevê|preve|registra|inicia|encerra|é|segue|seguem|prepara|convoca|homologa)\\s");
    /** Rodapé que o WordPress põe no resumo do feed. */
    private static final Pattern RODAPE_WORDPRESS = Pattern.compile("(?s)\\s*O post .* apareceu primeiro em .*$");
    private static final int IDADE_MAXIMA_DIAS = 30;
    private static final int MAX_ORGAO = 100;

    private final ClienteWeb web;
    private final List<Portal> portais;
    private final RegiaoRj regiao;
    private final Clock relogio;

    public FonteNoticias(ClienteWeb web, List<Portal> portais, RegiaoRj regiao, Clock relogio) {
        this.web = web;
        this.portais = List.copyOf(portais);
        this.regiao = regiao;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public List<PistaEncontrada> listar(Predicate<String> conhecida, List<String> avisos) {
        Map<String, PistaEncontrada> pistas = new LinkedHashMap<>();
        for (Portal portal : portais) {
            for (String feed : portal.feedsRj()) {
                ler(portal, feed, true, avisos).forEach(p -> pistas.putIfAbsent(p.url(), p));
            }
            for (String feed : portal.feeds()) {
                ler(portal, feed, false, avisos).forEach(p -> pistas.putIfAbsent(p.url(), p));
            }
        }
        return new ArrayList<>(pistas.values());
    }

    private List<PistaEncontrada> ler(Portal portal, String feed, boolean todasNoRj, List<String> avisos) {
        try {
            ClienteWeb.Resposta r = web.get(feed);
            if (!r.ok()) {
                avisos.add(portal.nome() + ": o feed respondeu HTTP " + r.status() + " (" + feed + ")");
                return List.of();
            }
            Document doc = Jsoup.parse(new ByteArrayInputStream(r.corpo()), null, r.urlFinal(), Parser.xmlParser());
            List<PistaEncontrada> lista = new ArrayList<>();
            for (Noticia n : noticias(doc)) {
                lista.add(pista(portal.nome(), n, todasNoRj));
            }
            return lista;
        } catch (IOException | RuntimeException e) {
            avisos.add(portal.nome() + ": falha ao ler o feed " + feed + " (" + e.getMessage() + ")");
            return List.of();
        }
    }

    record Noticia(String url, String titulo, String resumo, LocalDate data) {
    }

    /** Itens do feed: RSS 2.0 ({@code <item>}) ou Atom ({@code <entry>}). */
    static List<Noticia> noticias(Document doc) {
        List<Noticia> lista = new ArrayList<>();
        for (Element item : doc.select("item, entry")) {
            String url = semFragmento(link(item));
            String titulo = textoDe(item, "title");
            if (url == null || !url.startsWith("http") || titulo.isBlank()) {
                continue;
            }
            String resumo = RODAPE_WORDPRESS.matcher(textoDe(item, "description, summary")).replaceFirst("");
            lista.add(new Noticia(url, titulo, resumo, data(textoDe(item, "pubDate, published, updated"))));
        }
        return lista;
    }

    PistaEncontrada pista(String portal, Noticia n, boolean todasNoRj) {
        String titulo = Normalizador.compacto(n.titulo());
        String tudo = n.titulo() + " " + n.resumo();
        Matcher vagas = VAGAS.matcher(tudo);
        boolean doRj = todasNoRj || regiao.cita(tudo + " " + caminho(n.url()));
        PistaEncontrada p = new PistaEncontrada(portal, n.url(), n.titulo(), orgaoDoTitulo(n.titulo()),
                doRj ? "RJ" : null, vagas.find() ? vagas.group(1) + " vagas" : null, null, null, null);
        if (!doRj) {
            return p.descartada("Fora do RJ, segundo a notícia.");
        }
        if (NAO_E_EMPREGO.matcher(titulo).find()) {
            return p.descartada("Não é concurso para emprego (vestibular, curso, residência ou estágio).");
        }
        if (ETAPA_SEM_EDITAL.matcher(titulo).find()) {
            return p.descartada("A notícia trata de uma etapa sem edital aberto (previsão, banca, resultado, convocação...).");
        }
        if (!EDITAL_ABERTO.matcher(Normalizador.compacto(tudo)).find()) {
            return p.descartada("A notícia não fala de edital publicado nem de inscrições.");
        }
        if (n.data() != null && n.data().isBefore(LocalDate.now(relogio).minusDays(IDADE_MAXIMA_DIAS))) {
            return p.descartada("Notícia de " + n.data().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ", com mais de " + IDADE_MAXIMA_DIAS + " dias.");
        }
        return p;
    }

    /**
     * Órgão como a manchete escreve, para o robô se guiar no site da banca: "Concurso Prefeitura de
     * São Gonçalo (RJ) anuncia..." → "Prefeitura de São Gonçalo"; "RJ: Prefeitura de Tanguá abre..." →
     * "Prefeitura de Tanguá". Null quando a manchete não começa pelo órgão.
     */
    static String orgaoDoTitulo(String titulo) {
        String t = ESTADO_NO_INICIO.matcher(titulo.strip()).replaceFirst("");
        t = TIPO_NO_INICIO.matcher(t).replaceFirst("");
        Matcher fim = FIM_DO_ORGAO.matcher(t);
        if (fim.find()) {
            t = t.substring(0, fim.start());
        }
        t = t.replaceAll("\\s*\\((?:RJ|rj)\\)", "").strip();
        return t.length() < 3 || t.length() > MAX_ORGAO ? null : t;
    }

    private static String link(Element item) {
        Element atom = item.selectFirst("link[href]");
        if (atom != null) {
            return atom.attr("href").strip();
        }
        Element rss = item.selectFirst("link");
        return rss != null ? rss.text().strip() : null;
    }

    /** Texto do elemento; resumos vêm em HTML (CDATA), então as marcações saem. */
    private static String textoDe(Element item, String seletor) {
        Element e = item.selectFirst(seletor);
        return e == null ? "" : Jsoup.parse(e.text()).text().strip();
    }

    private static LocalDate data(String texto) {
        if (texto.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(texto, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(texto).toLocalDate();   // Atom: 2026-10-13T12:47:34-03:00
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String semFragmento(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** Caminho do endereço como palavras ("/n/concurso-guarda-de-queimados-rj" → "n concurso guarda de queimados rj"). */
    private static String caminho(String url) {
        try {
            String c = URI.create(url).getPath();
            return c == null ? "" : c.replaceAll("[/_.-]+", " ").strip();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
