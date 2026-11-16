package br.gov.crtrj.concursos.coleta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras do robots.txt de um site para o nosso robô (RFC 9309): vale o grupo com o nome do robô
 * (ex.: "ConcursosTecnicosBot") ou, se não houver, o grupo "*". Entre as regras que casam com o
 * caminho vence a mais longa; no empate, Allow. Aceita os curingas "*" e "$".
 */
public final class RegrasRobots {

    public static final RegrasRobots LIBERADO = new RegrasRobots(List.of());
    /** Site fora do ar ao pedir o robots.txt (erro 5xx): por segurança, nada é acessado. */
    public static final RegrasRobots BLOQUEADO = new RegrasRobots(List.of(new Regra(false, "/", Pattern.compile("/.*"))));

    private record Regra(boolean permite, String padrao, Pattern regex) {
    }

    private final List<Regra> regras;

    private RegrasRobots(List<Regra> regras) {
        this.regras = regras;
    }

    /** @param nomeRobo nome do robô no User-Agent, sem versão ("ConcursosTecnicosBot") */
    public static RegrasRobots ler(String conteudo, String nomeRobo) {
        List<Regra> doNosso = new ArrayList<>();
        List<Regra> deTodos = new ArrayList<>();
        boolean grupoNosso = false;
        boolean grupoTodos = false;
        boolean lendoAgentes = false;
        for (String bruta : conteudo.split("\\R")) {
            String linha = bruta.replaceFirst("#.*$", "").strip();
            int separador = linha.indexOf(':');
            if (separador < 0) {
                continue;
            }
            String campo = linha.substring(0, separador).strip().toLowerCase(Locale.ROOT);
            String valor = linha.substring(separador + 1).strip();
            switch (campo) {
                case "user-agent" -> {
                    if (!lendoAgentes) {
                        // um user-agent depois de regras abre um grupo novo
                        grupoNosso = false;
                        grupoTodos = false;
                        lendoAgentes = true;
                    }
                    grupoNosso |= valor.equalsIgnoreCase(nomeRobo);
                    grupoTodos |= valor.equals("*");
                }
                case "allow", "disallow" -> {
                    lendoAgentes = false;
                    if (valor.isEmpty()) {
                        continue;   // "Disallow:" vazio libera tudo
                    }
                    Regra r = new Regra(campo.equals("allow"), valor, compilar(valor));
                    if (grupoNosso) {
                        doNosso.add(r);
                    }
                    if (grupoTodos) {
                        deTodos.add(r);
                    }
                }
                default -> lendoAgentes = false;
            }
        }
        return new RegrasRobots(List.copyOf(!doNosso.isEmpty() || mencionaRobo(conteudo, nomeRobo) ? doNosso : deTodos));
    }

    /** Grupo com o nosso nome e sem regras = tudo liberado para nós (não cai no grupo "*"). */
    private static boolean mencionaRobo(String conteudo, String nomeRobo) {
        return Pattern.compile("(?im)^\\s*user-agent\\s*:\\s*" + Pattern.quote(nomeRobo) + "\\s*(#.*)?$").matcher(conteudo).find();
    }

    private static Pattern compilar(String padrao) {
        boolean fim = padrao.endsWith("$");
        String corpo = fim ? padrao.substring(0, padrao.length() - 1) : padrao;
        StringBuilder regex = new StringBuilder();
        for (String parte : corpo.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parte));
        }
        return Pattern.compile(regex + (fim ? "" : ".*"), Pattern.DOTALL);
    }

    /** @param caminho caminho com a query ("/uploads/edital.pdf?v=2"); vazio = "/" */
    public boolean permite(String caminho) {
        String alvo = caminho == null || caminho.isEmpty() ? "/" : caminho;
        if (alvo.equals("/robots.txt")) {
            return true;
        }
        Regra vencedora = null;
        for (Regra r : regras) {
            if (r.regex().matcher(alvo).matches()) {
                boolean maisLonga = vencedora == null || r.padrao().length() > vencedora.padrao().length();
                boolean empateAllow = vencedora != null && r.padrao().length() == vencedora.padrao().length() && r.permite();
                if (maisLonga || empateAllow) {
                    vencedora = r;
                }
            }
        }
        return vencedora == null || vencedora.permite();
    }
}
