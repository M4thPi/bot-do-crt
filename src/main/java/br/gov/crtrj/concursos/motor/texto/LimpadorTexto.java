package br.gov.crtrj.concursos.motor.texto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Limpeza do texto bruto das páginas: ligaduras, espaços especiais, hifenização de quebra de
 * linha e cabeçalhos/rodapés que se repetem em quase todas as páginas.
 */
public final class LimpadorTexto {

    private static final Pattern HIFEN_QUEBRA = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");
    private static final Pattern ESPACOS_FIM_LINHA = Pattern.compile("[ \\t]+\\n");
    private static final int LINHAS_BORDA = 3;

    private LimpadorTexto() {
    }

    public static List<String> limpar(List<String> paginasBrutas) {
        List<String> paginas = new ArrayList<>(paginasBrutas.size());
        for (String bruta : paginasBrutas) {
            paginas.add(limparPagina(bruta));
        }
        return removerCabecalhosRodapes(paginas);
    }

    static String limparPagina(String texto) {
        String t = texto.replace("\r\n", "\n").replace('\r', '\n')
                .replace("ﬁ", "fi").replace("ﬂ", "fl")
                .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace("­", "");
        t = HIFEN_QUEBRA.matcher(t).replaceAll("$1$2");
        t = ESPACOS_FIM_LINHA.matcher(t).replaceAll("\n");
        return t;
    }

    /**
     * Remove linhas das bordas (3 primeiras/3 últimas) que aparecem em pelo menos metade das
     * páginas, ignorando números (para pegar "Página 3 de 40").
     */
    static List<String> removerCabecalhosRodapes(List<String> paginas) {
        if (paginas.size() < 3) {
            return paginas;
        }
        Map<String, Integer> frequencia = new HashMap<>();
        for (String pagina : paginas) {
            for (String chave : chavesDeBorda(pagina)) {
                frequencia.merge(chave, 1, Integer::sum);
            }
        }
        int minimo = Math.max(3, (paginas.size() + 1) / 2);
        List<String> resultado = new ArrayList<>(paginas.size());
        // A 1ª página fica intacta: o cabeçalho repetido costuma trazer órgão e nº do edital,
        // que precisam de pelo menos uma ocorrência como evidência.
        resultado.add(paginas.get(0));
        for (String pagina : paginas.subList(1, paginas.size())) {
            String[] linhas = pagina.split("\n", -1);
            List<Integer> naoVazias = indicesNaoVazios(linhas);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < linhas.length; i++) {
                int pos = naoVazias.indexOf(i);
                boolean borda = pos >= 0 && (pos < LINHAS_BORDA || pos >= naoVazias.size() - LINHAS_BORDA);
                if (borda && frequencia.getOrDefault(chaveLinha(linhas[i]), 0) >= minimo) {
                    continue;
                }
                sb.append(linhas[i]).append('\n');
            }
            resultado.add(sb.toString());
        }
        return resultado;
    }

    private static List<String> chavesDeBorda(String pagina) {
        String[] linhas = pagina.split("\n", -1);
        List<Integer> naoVazias = indicesNaoVazios(linhas);
        List<String> chaves = new ArrayList<>();
        for (int pos = 0; pos < naoVazias.size(); pos++) {
            if (pos < LINHAS_BORDA || pos >= naoVazias.size() - LINHAS_BORDA) {
                String chave = chaveLinha(linhas[naoVazias.get(pos)]);
                if (!chaves.contains(chave)) {
                    chaves.add(chave);
                }
            }
        }
        return chaves;
    }

    private static List<Integer> indicesNaoVazios(String[] linhas) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < linhas.length; i++) {
            if (!linhas[i].isBlank()) {
                indices.add(i);
            }
        }
        return indices;
    }

    private static String chaveLinha(String linha) {
        return Normalizador.compacto(linha).replaceAll("\\d+", "#");
    }
}
