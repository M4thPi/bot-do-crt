package br.gov.crtrj.concursos.coleta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Internet em memória para os testes: URL → resposta (ou erro). Registra o que foi acessado. */
public final class WebFalsa implements ClienteWeb {

    private final Map<String, Object> paginas = new HashMap<>();
    private final List<String> bloqueados = new ArrayList<>();
    public final List<String> acessos = new ArrayList<>();

    public WebFalsa html(String url, String html) {
        paginas.put(url, html.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public WebFalsa pdf(String url, byte[] conteudo) {
        paginas.put(url, conteudo);
        return this;
    }

    public WebFalsa erro(String url, IOException erro) {
        paginas.put(url, erro);
        return this;
    }

    /** Endereços que começam assim são proibidos a robôs (como num robots.txt). */
    public WebFalsa bloquear(String prefixo) {
        bloqueados.add(prefixo);
        return this;
    }

    @Override
    public void verificarPermissao(String url) throws IOException {
        if (bloqueados.stream().anyMatch(url::startsWith)) {
            throw new AcessoNaoPermitido("O robots.txt não permite robôs em " + url);
        }
    }

    @Override
    public Resposta get(String url) throws IOException {
        verificarPermissao(url);
        acessos.add(url);
        Object conteudo = paginas.get(url);
        if (conteudo instanceof IOException e) {
            throw e;
        }
        if (conteudo == null) {
            return new Resposta(url, 404, "text/html", "não encontrado".getBytes(StandardCharsets.UTF_8));
        }
        return new Resposta(url, 200, null, (byte[]) conteudo);
    }

    /** Linha da listagem do PCI no formato real (div.da/.na com .ca .cc .cd .ce). */
    public static String linhaPci(String classe, String url, String orgao, String uf, String vagas, String cargos,
                                  String escolaridade, String prazo) {
        return "<div class=\"" + classe + "\" data-url=\"" + url + "\"><div class=\"ca\"><a href=\"" + url + "\" title=\""
                + orgao + " abre concurso\">" + orgao + "</a><div class=\"cb\"></div><div class=\"cc\">" + uf
                + "</div><div class=\"cd\">" + vagas + "<br><span>" + cargos + "<br><span>" + escolaridade
                + "</span></span></div><div class=\"ce\"><span>" + prazo + "</span></div></div></div>";
    }
}
