package br.gov.crtrj.concursos.coleta.dou;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lê o pacote diário do INLABS: um .zip com um XML por ato, no formato
 * {@code <xml><article pubName="DO3" artType="Edital" artCategory="Órgão/Unidade" pdfPage="..." idMateria="...">
 * <body><Identifica/><Ementa/><Texto>(HTML)</Texto></body></article></xml>}.
 *
 * <p>O pacote é lido em fluxo, ato por ato; só os que citam "concurso" são montados.
 */
public final class LeitorAtosDou {

    private static final int MAX_XML = 8 * 1024 * 1024;
    private static final DateTimeFormatter DATA_LINK = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private LeitorAtosDou() {
    }

    /** @return quantos atos o pacote tem (os que citam concurso vão para {@code atos}) */
    public static int ler(InputStream zip, LocalDate data, String secao, Consumer<AtoDou> atos) throws IOException {
        int total = 0;
        try (ZipInputStream z = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    continue;
                }
                total++;
                byte[] xml = z.readNBytes(MAX_XML);
                // filtro barato antes de montar o XML: a maior parte do DO3 é licitação e contrato
                if (!new String(xml, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).contains("concurso")) {
                    continue;
                }
                Document doc = Jsoup.parse(new ByteArrayInputStream(xml), null, "", Parser.xmlParser());
                for (Element artigo : doc.select("article")) {
                    atos.accept(ato(artigo, data, secao));
                }
            }
        }
        return total;
    }

    static AtoDou ato(Element artigo, LocalDate data, String secao) {
        String id = primeiro(artigo.attr("idMateria"), artigo.attr("id"));
        String pagina = artigo.attr("pdfPage").strip();
        String link = (pagina.isEmpty()
                ? "https://www.in.gov.br/leiturajornal?data=" + data.format(DATA_LINK) + "&secao=" + secao.toLowerCase(Locale.ROOT)
                : pagina) + "#materia-" + id;
        return new AtoDou(id, data, primeiro(artigo.attr("pubName"), secao), artigo.attr("artType").strip(),
                artigo.attr("artCategory").strip(), html(artigo, "Identifica"), html(artigo, "Ementa"), html(artigo, "Texto"),
                link);
    }

    /** Conteúdo do campo (texto ou HTML dentro de CDATA) sem as marcas. */
    private static String html(Element artigo, String campo) {
        Element e = artigo.selectFirst(campo.toLowerCase(Locale.ROOT));
        if (e == null) {
            return "";
        }
        return Jsoup.parse(e.wholeText()).text().strip();
    }

    private static String primeiro(String a, String b) {
        return a == null || a.isBlank() ? b : a.strip();
    }
}
