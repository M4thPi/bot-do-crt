package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descoberta nos sites das bancas organizadoras. A página de concursos de cada banca tem um
 * link para a página de cada concurso; o link é reconhecido por uma expressão configurada por
 * banca, e o texto em volta dele (o "cartão") diz órgão, cidade, UF e situação. Ficam os
 * concursos do RJ que não terminaram; os demais viram pista descartada, com o motivo.
 *
 * <p>A página do concurso na banca é fonte oficial: o edital é procurado nela.
 */
public final class FonteBancas implements FonteDescoberta {

    /**
     * @param listagens  páginas da banca que listam concursos
     * @param link       expressão que reconhece o endereço da página de um concurso
     * @param todasNoRj  banca só do RJ (ex.: CEPUERJ): não precisa citar o estado no cartão
     */
    public record Banca(String nome, List<String> listagens, Pattern link, boolean todasNoRj) {
    }

    public static final String NOME = "Bancas";

    private static final Pattern NAO_E_EMPREGO = Pattern.compile(
            "vestibular|residencia|ingresso nos cursos|cursos tecnicos|educacao profissional tecnica|monografia|estagi");
    private static final Pattern ENCERRADO = Pattern.compile("conclui|encerrad|finalizad|resultado final|homologa|cancelad");
    private static final Pattern ABERTO = Pattern.compile("inscricoes abertas|inscricao aberta|em inscricao|aberto|em breve|previsto");
    private static final Pattern ANO = Pattern.compile("(?<!\\d)(20\\d{2})(?!\\d)");
    /** Ano de 2 dígitos no fim do endereço ("/concursos/cbmerj22", "pgm.niteroi23"). */
    private static final Pattern ANO_CURTO_NO_ENDERECO = Pattern.compile("[a-z](\\d{2})/?$");
    private static final Pattern PRAZO = Pattern.compile("(?i)at[ée]\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4})");
    /** Cartão sem texto (só imagem): no máximo tantas páginas abertas por rodada para ler o título. */
    private static final int MAX_TITULOS_POR_RODADA = 10;
    private static final int MIN_LETRAS_CARTAO = 15;
    private static final int MAX_CARTAO = 700;

    private final ClienteWeb web;
    private final List<Banca> bancas;
    private final RegiaoRj regiao;
    private final Clock relogio;

    public FonteBancas(ClienteWeb web, List<Banca> bancas, RegiaoRj regiao, Clock relogio) {
        this.web = web;
        this.bancas = List.copyOf(bancas);
        this.regiao = regiao;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return NOME;
    }

    @Override
    public List<PistaEncontrada> listar(Predicate<String> conhecida, List<String> avisos) {
        List<PistaEncontrada> pistas = new ArrayList<>();
        int titulosAbertos = 0;
        for (Banca banca : bancas) {
            for (String listagem : banca.listagens()) {
                Map<String, String> cartoes;
                try {
                    ClienteWeb.Resposta r = web.get(listagem);
                    if (!r.ok()) {
                        avisos.add(banca.nome() + ": a página de concursos respondeu HTTP " + r.status() + " (" + listagem + ")");
                        continue;
                    }
                    cartoes = cartoes(Jsoup.parse(new ByteArrayInputStream(r.corpo()), null, r.urlFinal()), banca.link());
                } catch (IOException | RuntimeException e) {
                    avisos.add(banca.nome() + ": falha ao ler " + listagem + " (" + e.getMessage() + ")");
                    continue;
                }
                for (Map.Entry<String, String> c : cartoes.entrySet()) {
                    String url = c.getKey();
                    String texto = c.getValue();
                    if (letras(texto) < MIN_LETRAS_CARTAO) {
                        // cartão só com imagem: o título está na página do concurso (lida uma vez só)
                        if (conhecida.test(url) || titulosAbertos >= MAX_TITULOS_POR_RODADA) {
                            continue;
                        }
                        titulosAbertos++;
                        texto = tituloDaPagina(url);
                        if (texto == null) {
                            continue;
                        }
                    }
                    pistas.add(pista(banca, url, texto));
                }
            }
        }
        return pistas;
    }

    /**
     * Endereço do concurso → texto do cartão: o maior bloco em volta do link que não contém
     * link de outro concurso (cada cartão da listagem fica separado do vizinho).
     */
    static Map<String, String> cartoes(Document doc, Pattern link) {
        Map<String, String> cartoes = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String destino = semFragmento(a.absUrl("href"));
            if (destino.isEmpty() || !link.matcher(destino).find()) {
                continue;
            }
            Element bloco = a;
            String texto = a.text() + " " + a.attr("title");
            while (bloco.parent() != null && !bloco.parent().tagName().equals("body")) {
                Element pai = bloco.parent();
                boolean temOutroConcurso = pai.select("a[href]").stream()
                        .map(x -> semFragmento(x.absUrl("href")))
                        .anyMatch(x -> !x.equals(destino) && link.matcher(x).find());
                if (temOutroConcurso || pai.text().length() > MAX_CARTAO) {
                    break;
                }
                bloco = pai;
                texto = pai.text();
            }
            String limpo = texto.replaceAll("\\s+", " ").strip();
            cartoes.merge(destino, limpo, (x, y) -> x.length() >= y.length() ? x : y);
        }
        return cartoes;
    }

    PistaEncontrada pista(Banca banca, String url, String texto) {
        String alvo = Normalizador.compacto(texto);
        String termoRj = regiao.termoCitado(texto + " " + caminho(url));
        Matcher prazo = PRAZO.matcher(texto);
        String resumo = texto.length() > 160 ? texto.substring(0, 157) + "..." : texto;
        PistaEncontrada p = new PistaEncontrada(banca.nome(), url, texto, resumo,
                banca.todasNoRj() || termoRj != null ? "RJ" : null, null, null, null,
                prazo.find() ? prazo.group(1) : null);
        if (!banca.todasNoRj() && termoRj == null) {
            return p.descartada("Fora do RJ, segundo a página da banca.");
        }
        if (NAO_E_EMPREGO.matcher(alvo).find()) {
            return p.descartada("Não é concurso para emprego (vestibular, curso, residência ou estágio).");
        }
        boolean aberto = ABERTO.matcher(alvo).find();
        if (!aberto && ENCERRADO.matcher(alvo).find()) {
            return p.descartada("A banca informa que o concurso terminou ou as inscrições se encerraram.");
        }
        int ano = anoMaisRecente(alvo + " " + caminho(url));
        int anoAtual = LocalDate.now(relogio).getYear();
        if (!aberto && ano > 0 && ano < anoAtual) {
            return p.descartada("Concurso de " + ano + " sem inscrições abertas na página da banca.");
        }
        return p;
    }

    private static int anoMaisRecente(String texto) {
        int maior = 0;
        Matcher m = ANO.matcher(texto);
        while (m.find()) {
            maior = Math.max(maior, Integer.parseInt(m.group(1)));
        }
        Matcher curto = ANO_CURTO_NO_ENDERECO.matcher(texto.strip());
        if (curto.find()) {
            maior = Math.max(maior, 2000 + Integer.parseInt(curto.group(1)));
        }
        return maior;
    }

    private String tituloDaPagina(String url) {
        try {
            ClienteWeb.Resposta r = web.get(url);
            if (!r.ok()) {
                return null;
            }
            Document doc = Jsoup.parse(new ByteArrayInputStream(r.corpo()), null, r.urlFinal());
            Element og = doc.selectFirst("meta[property=og:title]");
            Element h1 = doc.selectFirst("h1, h2");
            String titulo = String.join(" ", og == null ? "" : og.attr("content"), h1 == null ? "" : h1.text(), doc.title());
            return letras(titulo) < MIN_LETRAS_CARTAO ? null : titulo.replaceAll("\\s+", " ").strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int letras(String s) {
        return s == null ? 0 : s.replaceAll("[^\\p{L}]", "").length();
    }

    private static String semFragmento(String url) {
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** Caminho do endereço como palavras ("/concurso/prefeitura-de-marica-rj/" → "concurso prefeitura de marica rj"). */
    private static String caminho(String url) {
        try {
            String c = URI.create(url).getPath();
            return c == null ? "" : c.replaceAll("[/_.-]+", " ").strip();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
