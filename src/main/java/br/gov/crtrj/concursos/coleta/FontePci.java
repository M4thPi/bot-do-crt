package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Descoberta pelo PCI Concursos (só pista — o edital sempre vem do site oficial; o robots.txt do
 * PCI proíbe baixar os PDFs de lá). Lê a listagem regional e guarda as linhas das UFs pedidas.
 *
 * <p>Estrutura de cada linha: {@code div.da|div.na} com {@code .ca a} (órgão + link da notícia),
 * {@code .cc} (UF), {@code .cd} (vagas, cargos e escolaridade separados por &lt;br&gt;) e
 * {@code .ce} (inscrição até).
 */
public final class FontePci implements FonteDescoberta {

    public static final String NOME = "PCI";

    private final ClienteWeb web;
    private final List<String> listagens;
    private final Set<String> ufs;

    /**
     * @param listagens páginas regionais (ex.: https://www.pciconcursos.com.br/concursos/sudeste/)
     * @param ufs       UFs a manter, em maiúsculas ("RJ"); "NACIONAL" mantém os concursos nacionais
     */
    public FontePci(ClienteWeb web, List<String> listagens, Set<String> ufs) {
        this.web = web;
        this.listagens = List.copyOf(listagens);
        this.ufs = Set.copyOf(ufs);
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public List<PistaEncontrada> listar(Predicate<String> conhecida, List<String> avisos) throws IOException {
        return listar();
    }

    public List<PistaEncontrada> listar() throws IOException {
        List<PistaEncontrada> pistas = new ArrayList<>();
        for (String url : listagens) {
            ClienteWeb.Resposta resposta = web.get(url);
            if (!resposta.ok()) {
                throw new IOException("PCI respondeu HTTP " + resposta.status() + " em " + url);
            }
            pistas.addAll(ler(resposta.corpo(), resposta.urlFinal()));
        }
        return pistas;
    }

    List<PistaEncontrada> ler(byte[] html, String urlBase) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(html), null, urlBase);
        List<PistaEncontrada> pistas = new ArrayList<>();
        for (Element linha : doc.select("div.da, div.na")) {
            Element link = linha.selectFirst(".ca a[href]");
            Element uf = linha.selectFirst(".cc");
            if (link == null || uf == null) {
                continue;
            }
            String siglaUf = uf.text().strip();
            if (!ufs.contains(Normalizador.chave(siglaUf).toUpperCase())) {
                continue;
            }
            String[] detalhes = partes(linha.selectFirst(".cd"));
            Element prazo = linha.selectFirst(".ce");
            PistaEncontrada p = new PistaEncontrada(NOME, link.absUrl("href"), link.attr("title"), link.text().strip(), siglaUf,
                    detalhes[0], detalhes[1], detalhes[2], prazo == null ? null : prazo.text().strip());
            pistas.add(podeTerCargoTecnico(p) ? p : p.descartada("A listagem indica apenas: " + p.escolaridade()));
        }
        return pistas;
    }

    /** ".cd" traz "145 vagas até R$ X<br><span>Cargos<br><span>Escolaridade</span></span>". */
    private static String[] partes(Element cd) {
        String[] resultado = new String[3];
        if (cd == null) {
            return resultado;
        }
        String[] pedacos = cd.html().split("(?i)<br\\s*/?>");
        for (int i = 0; i < Math.min(3, pedacos.length); i++) {
            resultado[i] = Jsoup.parse(pedacos[i]).text().strip();
        }
        return resultado;
    }

    /**
     * Vale abrir a notícia? Descarta só o que a própria listagem mostra não ter nível técnico
     * (ex.: "Superior", "Professor"): na dúvida, mantém.
     */
    public static boolean podeTerCargoTecnico(PistaEncontrada p) {
        String esc = Normalizador.normalizar(p.escolaridade() == null ? "" : p.escolaridade());
        String cargos = Normalizador.normalizar(p.cargos() == null ? "" : p.cargos());
        if (esc.isBlank() || esc.contains("tecnic")) {
            return true;
        }
        return esc.contains("medio") && (cargos.contains("tecnic") || cargos.contains("varios"));
    }
}
