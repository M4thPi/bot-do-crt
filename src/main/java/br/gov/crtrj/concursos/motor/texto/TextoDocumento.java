package br.gov.crtrj.concursos.motor.texto;

import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texto do documento inteiro (páginas concatenadas), em duas versões com o mesmo comprimento:
 * original (para exibir e citar) e normalizada (para buscar). Também guarda a estrutura do
 * edital — itens numerados e seções (anexos, capítulos) — para localizar cada evidência.
 */
public final class TextoDocumento {

    private static final Pattern ITEM = Pattern.compile("^\\s*(\\d{1,2}(?:\\.\\d{1,3}){1,5})\\.?\\s+\\S");
    private static final Pattern ITEM_TOPO = Pattern.compile("^\\s*(\\d{1,2})\\s*[.)\\-–]\\s+[a-z]");
    private static final Pattern ALINEA = Pattern.compile("^\\s*([a-z])\\)\\s+\\S");
    private static final Pattern ANEXO = Pattern.compile("^\\s*anexo\\s+([ivxlc]+|\\d{1,2}|unico)\\b\\s*[-–:.]?\\s*(.*)$");
    private static final Pattern CAPITULO = Pattern.compile("^\\s*capitulo\\s+([ivxlc]+|\\d{1,2})\\b\\s*[-–:.]?\\s*(.*)$");
    private static final Pattern TITULO_EXCLUIDO = Pattern.compile(
            "^\\s*(conteudos? programaticos?|programas? (de|das) provas?|atribuicoes( sumarias| dos cargos| dos empregos| das funcoes)?"
                    + "|descricao (sumaria )?das atribuicoes|sintese das atribuicoes|bibliografia( sugerida)?)\\b.{0,80}$");
    /** Anexos/seções que não trazem requisitos de cargo. */
    private static final Pattern PALAVRAS_EXCLUSAO = Pattern.compile(
            "conteudo|programa|atribuic|bibliografia|cronograma|modelo|requerimento|declaracao|formulario|laudo|procuracao|recurso|endereco");

    /** Linha que começa item, alínea ou rótulo ("2.1 ", "a) ", "REQUISITOS:", "Carga horária:"): fronteira de trecho. */
    private static final Pattern INICIO_DE_BLOCO = Pattern.compile(
            "^[ \\t]*(?:\\d{1,4}(?:\\.\\d{1,3})*[.)\\-–]?\\s|[a-z]\\)\\s|[A-ZÀ-Ü][A-ZÀ-Ü /]{2,40}:|[A-ZÀ-Ü][a-zà-ü]+(?: [a-zà-ü]+){0,3}:)");

    private static final int MAX_TRECHO = 350;

    private final List<String> paginas;
    private final String original;
    private final String normalizado;
    private final int[] inicioPagina;
    private final List<Linha> linhas = new ArrayList<>();
    private final List<Marca> itens = new ArrayList<>();
    private final List<Secao> secoes = new ArrayList<>();

    /** Linha no texto global: [inicio, fim), sem o '\n'. */
    public record Linha(int inicio, int fim) {
    }

    /** Seção de nível alto. {@code excluida} = não contém requisitos (conteúdo programático, atribuições...). */
    public record Secao(int inicio, String rotulo, String titulo, boolean excluida) {
    }

    private record Marca(int offset, String rotulo) {
    }

    private TextoDocumento(List<String> paginas) {
        this.paginas = List.copyOf(paginas);
        this.inicioPagina = new int[paginas.size()];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paginas.size(); i++) {
            inicioPagina[i] = sb.length();
            sb.append(paginas.get(i));
            if (sb.isEmpty() || sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
        this.original = sb.toString();
        this.normalizado = Normalizador.normalizar(original);
        indexarLinhas();
        segmentar();
    }

    public static TextoDocumento de(List<String> paginasLimpas) {
        return new TextoDocumento(paginasLimpas);
    }

    // ------------------------------------------------------------------ acesso

    public String original() {
        return original;
    }

    public String normalizado() {
        return normalizado;
    }

    public int totalPaginas() {
        return paginas.size();
    }

    public String textoPagina(int pagina) {
        return paginas.get(pagina - 1);
    }

    public List<Linha> linhas() {
        return Collections.unmodifiableList(linhas);
    }

    public List<Secao> secoes() {
        return Collections.unmodifiableList(secoes);
    }

    public String linhaOriginal(Linha l) {
        return original.substring(l.inicio(), l.fim());
    }

    public String linhaNormalizada(Linha l) {
        return normalizado.substring(l.inicio(), l.fim());
    }

    /** Página (1-based) que contém o offset global. */
    public int pagina(int offset) {
        int idx = Arrays.binarySearch(inicioPagina, offset);
        if (idx >= 0) {
            // páginas vazias compartilham o mesmo início: fica com a última delas
            while (idx + 1 < inicioPagina.length && inicioPagina[idx + 1] == offset) {
                idx++;
            }
            return idx + 1;
        }
        return Math.max(1, -idx - 1);
    }

    public int inicioDaPagina(int pagina) {
        return inicioPagina[pagina - 1];
    }

    public int fimDaPagina(int pagina) {
        return pagina < inicioPagina.length ? inicioPagina[pagina] : original.length();
    }

    public Secao secaoEm(int offset) {
        Secao atual = null;
        for (Secao s : secoes) {
            if (s.inicio() > offset) {
                break;
            }
            atual = s;
        }
        return atual;
    }

    public boolean emSecaoExcluida(int offset) {
        Secao s = secaoEm(offset);
        return s != null && s.excluida();
    }

    /** Item numerado mais próximo antes do offset, dentro da mesma seção. */
    public String itemEm(int offset) {
        Secao secao = secaoEm(offset);
        int limite = secao == null ? 0 : secao.inicio();
        String rotulo = null;
        for (Marca m : itens) {
            if (m.offset() > offset) {
                break;
            }
            rotulo = m.offset() >= limite ? m.rotulo() : null;
        }
        return rotulo;
    }

    // ------------------------------------------------------------------ evidências

    public Evidencia evidencia(int inicio, int fim, MetodoExtracao metodo) {
        Secao secao = secaoEm(inicio);
        return new Evidencia(pagina(inicio), secao == null ? null : secao.rotulo(), itemEm(inicio),
                trecho(inicio, fim), metodo, 1.0);
    }

    /** Trecho literal: a frase que contém [inicio, fim), ou a linha, se a frase for longa demais. */
    public String trecho(int inicio, int fim) {
        int[] faixa = sentenca(inicio, fim);
        if (faixa[1] - faixa[0] > MAX_TRECHO) {
            faixa = new int[]{inicioLinha(inicio), fimLinha(Math.max(inicio, fim - 1))};
            if (faixa[1] - faixa[0] > MAX_TRECHO) {
                faixa = new int[]{Math.max(faixa[0], inicio - 150), Math.min(faixa[1], fim + 150)};
            }
        }
        return compactar(original.substring(faixa[0], faixa[1]));
    }

    /**
     * Expande até o fim de frase (". " ou "; "), linha em branco, ou início de outro item/rótulo —
     * para que o trecho de um cargo não carregue linhas do item anterior.
     */
    public int[] sentenca(int inicio, int fim) {
        int a = inicio;
        int limiteIni = Math.max(0, inicio - 300);
        while (a > limiteIni) {
            char c = original.charAt(a - 1);
            if ((c == '.' || c == ';') && Character.isWhitespace(original.charAt(a))) {
                break;
            }
            if (c == '\n' && (iniciaBloco(a) || (a >= 2 && original.charAt(a - 2) == '\n'))) {
                break;
            }
            a--;
        }
        int b = Math.max(fim, a);
        int limiteFim = Math.min(original.length(), fim + 300);
        while (b < limiteFim) {
            char c = original.charAt(b);
            if ((c == '.' || c == ';') && (b + 1 >= original.length() || Character.isWhitespace(original.charAt(b + 1)))) {
                b++;
                break;
            }
            if (c == '\n' && b + 1 < original.length() && (original.charAt(b + 1) == '\n' || iniciaBloco(b + 1))) {
                break;
            }
            b++;
        }
        return new int[]{a, b};
    }

    private boolean iniciaBloco(int inicioLinha) {
        int fimLinha = original.indexOf('\n', inicioLinha);
        String linha = original.substring(inicioLinha, fimLinha < 0 ? original.length() : fimLinha);
        return INICIO_DE_BLOCO.matcher(linha).find();
    }

    public int inicioLinha(int offset) {
        int i = original.lastIndexOf('\n', Math.max(0, offset - 1));
        return i < 0 ? 0 : i + 1;
    }

    public int fimLinha(int offset) {
        int i = original.indexOf('\n', offset);
        return i < 0 ? original.length() : i;
    }

    public static String compactar(String texto) {
        return texto.replaceAll("\\s+", " ").trim();
    }

    /** Proporção de letras maiúsculas entre as letras da linha. */
    public static double proporcaoMaiusculas(String linha) {
        int letras = 0;
        int maiusculas = 0;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (Character.isLetter(c)) {
                letras++;
                if (Character.isUpperCase(c)) {
                    maiusculas++;
                }
            }
        }
        return letras == 0 ? 0 : (double) maiusculas / letras;
    }

    // ------------------------------------------------------------------ estrutura

    private void indexarLinhas() {
        int inicio = 0;
        for (int i = 0; i < original.length(); i++) {
            if (original.charAt(i) == '\n') {
                linhas.add(new Linha(inicio, i));
                inicio = i + 1;
            }
        }
        if (inicio < original.length()) {
            linhas.add(new Linha(inicio, original.length()));
        }
    }

    private void segmentar() {
        String ultimoItem = null;
        for (int i = 0; i < linhas.size(); i++) {
            Linha linha = linhas.get(i);
            String norm = linhaNormalizada(linha);
            String orig = linhaOriginal(linha);
            if (norm.isBlank()) {
                continue;
            }
            Secao secao = detectarSecao(i, norm, orig);
            if (secao != null) {
                secoes.add(secao);
                continue;
            }
            Matcher m = ITEM.matcher(norm);
            if (m.find()) {
                ultimoItem = m.group(1);
                itens.add(new Marca(linha.inicio(), ultimoItem));
                continue;
            }
            m = ITEM_TOPO.matcher(norm);
            if (m.find() && proporcaoMaiusculas(orig) >= 0.6) {
                ultimoItem = m.group(1);
                itens.add(new Marca(linha.inicio(), ultimoItem));
                continue;
            }
            m = ALINEA.matcher(norm);
            if (m.find() && ultimoItem != null) {
                String base = ultimoItem.contains(".") && Character.isLetter(ultimoItem.charAt(ultimoItem.length() - 1))
                        ? ultimoItem.substring(0, ultimoItem.lastIndexOf('.'))
                        : ultimoItem;
                ultimoItem = base + "." + m.group(1);
                itens.add(new Marca(linha.inicio(), ultimoItem));
            }
        }
    }

    private Secao detectarSecao(int indiceLinha, String norm, String orig) {
        if (orig.strip().length() > 120) {
            return null;
        }
        boolean destaque = proporcaoMaiusculas(orig) >= 0.6;
        Linha linha = linhas.get(indiceLinha);

        Matcher m = ANEXO.matcher(norm);
        if (m.find() && (destaque || m.group(2).isBlank())) {
            String titulo = tituloDaSecao(indiceLinha, m.start(2), m.group(2));
            String rotulo = "Anexo " + m.group(1).toUpperCase();
            boolean excluida = semRequisitos(titulo);
            return new Secao(linha.inicio(), rotulo, titulo, excluida);
        }
        m = CAPITULO.matcher(norm);
        if (m.find() && (destaque || m.group(2).isBlank())) {
            String titulo = tituloDaSecao(indiceLinha, m.start(2), m.group(2));
            String rotulo = "Capítulo " + m.group(1).toUpperCase();
            boolean excluida = semRequisitos(titulo);
            return new Secao(linha.inicio(), rotulo, titulo, excluida);
        }
        if (destaque && TITULO_EXCLUIDO.matcher(norm).find()) {
            return new Secao(linha.inicio(), compactar(orig), compactar(orig), true);
        }
        return null;
    }

    /**
     * Anexo sem requisitos de cargo (conteúdo programático, cronograma, modelos...). Um anexo de
     * "atribuições e requisitos" não é excluído: é justamente onde muitos editais põem o requisito.
     */
    private static boolean semRequisitos(String titulo) {
        String norm = Normalizador.normalizar(titulo);
        return PALAVRAS_EXCLUSAO.matcher(norm).find() && !norm.matches("(?s).*(requisit|escolaridade|habilitac|formacao).*");
    }

    /** Título na mesma linha do "ANEXO I" ou, se vazio, na próxima linha não vazia. */
    private String tituloDaSecao(int indiceLinha, int inicioNaLinha, String resto) {
        Linha linha = linhas.get(indiceLinha);
        if (!resto.isBlank()) {
            return compactar(original.substring(linha.inicio() + inicioNaLinha, linha.fim()));
        }
        for (int j = indiceLinha + 1; j < linhas.size() && j <= indiceLinha + 3; j++) {
            String proxima = linhaOriginal(linhas.get(j));
            if (!proxima.isBlank()) {
                return compactar(proxima);
            }
        }
        return "";
    }
}
