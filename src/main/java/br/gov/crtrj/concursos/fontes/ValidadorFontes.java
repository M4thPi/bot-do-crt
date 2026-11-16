package br.gov.crtrj.concursos.fontes;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Diferencia fonte oficial (órgão, banca, diário oficial) de fonte secundária (agregadores e
 * notícias, que servem só para descoberta).
 */
public final class ValidadorFontes {

    public enum TipoFonte {
        ORGAO_OFICIAL(true),
        BANCA(true),
        CONFIRMADA_MANUALMENTE(true),
        AGREGADOR(false),
        DESCONHECIDA(false),
        ENVIO_MANUAL(false);

        private final boolean oficial;

        TipoFonte(boolean oficial) {
            this.oficial = oficial;
        }

        public boolean oficial() {
            return oficial;
        }
    }

    private final List<String> sufixosOficiais;
    private final List<String> dominiosBancas;
    private final List<String> dominiosOrgaos;
    private final List<String> dominiosAgregadores;

    public ValidadorFontes(List<String> sufixosOficiais, List<String> dominiosBancas, List<String> dominiosOrgaos,
                           List<String> dominiosAgregadores) {
        this.sufixosOficiais = normalizar(sufixosOficiais);
        this.dominiosBancas = normalizar(dominiosBancas);
        this.dominiosOrgaos = normalizar(dominiosOrgaos);
        this.dominiosAgregadores = normalizar(dominiosAgregadores);
    }

    /** Listas iniciais — revisar e ampliar em application.yml. */
    public static ValidadorFontes padrao() {
        return new ValidadorFontes(
                List.of(".gov.br", ".jus.br", ".mp.br", ".leg.br", ".mil.br", ".def.br", ".edu.br"),
                List.of("cesgranrio.org.br", "fgv.br", "cebraspe.org.br", "ibfc.org.br", "institutoaocp.org.br",
                        "selecon.org.br", "selecao.net.br", "ibade.org.br", "ibam-concursos.org.br", "consulplan.net",
                        "institutoconsulplan.org.br", "idecan.org.br", "concursosfcc.com.br", "vunesp.com.br",
                        "quadrix.org.br", "fundatec.org.br", "institutoaccess.org.br", "access.org.br", "ian.org.br",
                        "ibade.selecao.site"),
                List.of("petrobras.com.br", "transpetro.com.br", "cedae.com.br", "uff.br", "ufrj.br", "uerj.br",
                        "unirio.br", "ufrrj.br", "uenf.br", "cefet-rj.br", "prefeitura.rio", "cp2.g12.br"),
                List.of("pciconcursos.com.br", "jcconcursos.com.br", "folhadirigida.com.br", "qconcursos.com",
                        "estrategiaconcursos.com.br", "grancursosonline.com.br", "concursosnobrasil.com"));
    }

    public TipoFonte classificar(String url) {
        if (url == null || url.isBlank()) {
            return TipoFonte.ENVIO_MANUAL;
        }
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return TipoFonte.DESCONHECIDA;
        }
        if (host == null) {
            return TipoFonte.DESCONHECIDA;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (pertence(host, dominiosAgregadores)) {
            return TipoFonte.AGREGADOR;
        }
        if (pertence(host, dominiosBancas)) {
            return TipoFonte.BANCA;
        }
        if (pertence(host, dominiosOrgaos)) {
            return TipoFonte.ORGAO_OFICIAL;
        }
        for (String sufixo : sufixosOficiais) {
            if (host.endsWith(sufixo)) {
                return TipoFonte.ORGAO_OFICIAL;
            }
        }
        return TipoFonte.DESCONHECIDA;
    }

    private static boolean pertence(String host, List<String> dominios) {
        return dominios.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
    }

    private static List<String> normalizar(List<String> lista) {
        return lista == null ? List.of() : lista.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).toList();
    }
}
