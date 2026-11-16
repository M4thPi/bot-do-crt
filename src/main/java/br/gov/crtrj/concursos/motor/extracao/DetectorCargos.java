package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento.Linha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localiza blocos de cargo no edital. Busca com alto recall: o que decide se a vaga serve para
 * técnico industrial é a análise do requisito, não este detector.
 *
 * <p>Reconhece dois formatos de cabeçalho:
 * <ul>
 *   <li>rotulado: "CARGO: Técnico em Eletrotécnica", "Cargo 12 – Técnico de Manutenção", "EMPREGO: ..."</li>
 *   <li>título técnico: linha iniciada por "Técnico ..." com cara de título (maiúsculas, numerada ou após fim de frase)</li>
 * </ul>
 */
public final class DetectorCargos {

    private static final Pattern CABECALHO_ROTULADO = Pattern.compile(
            "^\\s*(?:\\d+(?:\\.\\d+)*\\s*[-.)–]?\\s*)?"
                    + "(cargos?|empregos?|funcao|funcoes|especialidade|perfil|ocupacao|area de atuacao)"
                    + "(?:\\s*/\\s*(?:especialidade|area|funcao|perfil|cargo))?"
                    + "\\s*(?:n[o.]?\\s*)?(?:\\d{1,4}\\s*)?[:\\-–]\\s*(\\S.{2,200})$");
    /** Nome de cargo é singular: "Técnicas de montagem" (conteúdo programático) não é cargo. */
    private static final Pattern CABECALHO_TECNICO = Pattern.compile(
            "^\\s*((?:\\d+(?:\\.\\d+)*|cod(?:igo)?\\.?\\s*\\d+|\\d{2,4})\\s*[-.)–:]?\\s*)?(tecnic[oa]\\b.{2,160})$");
    /** Célula de tabela que quebra o nome em duas linhas: "Técnico" / "Audiovisual", "Técnico em" / "Enfermagem". */
    private static final Pattern CABECALHO_PARTIDO = Pattern.compile(
            "^\\s*(?:\\d{1,4}\\s+)?(tecnic[oa](?: (?:em|de|do|da|de nivel medio))?)\\s*$");
    private static final Pattern CONTINUACAO_NOME = Pattern.compile("^\\s*\\p{Lu}[^\\d:;]{1,40}$");
    /**
     * Rótulos que abrem a ficha de um cargo. Uma linha curta logo acima deles é o nome do cargo, mesmo
     * sem "Cargo:" nem "Técnico" — ex.: "Fiscal de Obras" / "Descrição Sumária: ..." / "Requisitos: Técnico em Edificações".
     */
    private static final Pattern ROTULO_DE_FICHA = Pattern.compile(
            "^\\s*(descricao(?: sumaria)?|atribuicoes?(?: tipicas| sumarias| do cargo)?|requisitos?(?: para provimento| minimos| basicos| de escolaridade)?"
                    + "|pre-?requisitos?|escolaridade(?: minima| exigida)?|formacao exigida)\\s*[:\\-–]");
    private static final Pattern NOME_DE_FICHA = Pattern.compile("^\\s*\\p{Lu}[^:;]{2,80}$");
    /** "5. DAS INSCRIÇÕES", "6 – DO CONCURSO": fecham o bloco de cargo anterior. */
    private static final Pattern FRONTEIRA = Pattern.compile("^\\s*\\d{1,2}\\s*[.)\\-–]\\s+d[oa]s?\\s+\\p{L}");
    /** Nome "rotulado" que na verdade é cabeçalho de tabela. */
    private static final Pattern NOME_INVALIDO = Pattern.compile(
            "^(requisito|vaga|escolaridade|remuneracao|salario|vencimento|carga|codigo|total|atribuic|quantidade|nivel)");
    private static final Pattern FRASE = Pattern.compile("\\s(sera|serao|deve|deverao|podera|poderao|devera|e o|sao)\\s");
    /** Onde termina o nome do cargo numa linha de tabela que emenda nome + requisito. */
    private static final Pattern CORTE_NOME = Pattern.compile(
            "\\s(?:certificado|curso|ensino|diploma|escolaridade|registro|requisitos?|r\\$|\\d+\\s*\\(\\s*\\p{L}+\\s*\\)\\s*vagas?|\\d+\\s+vagas?|\\d{2}\\s*h(?:oras)?\\b)"
                    // marcadores de nota ("Caxias²" vira "caxias2" no texto normalizado) e números soltos da tabela
                    + "|\\*|(?<=\\p{L})\\d|\\s\\d{1,4}(?![\\d.,/])");
    private static final Pattern SEPARADOR_ESPECIALIDADE = Pattern.compile("\\s[–-]\\s|:\\s");

    private static final int MAX_BLOCO = 4000;
    private static final int DISTANCIA_ESPECIALIDADE = 400;

    private static final class Cabecalho {
        final Linha linha;
        final int inicioNome;
        final int fimNome;
        int inicioEspecialidade = -1;
        int fimEspecialidade = -1;

        Cabecalho(Linha linha, int inicioNome, int fimNome) {
            this.linha = linha;
            this.inicioNome = inicioNome;
            this.fimNome = fimNome;
        }
    }

    public List<CargoDetectado> detectar(TextoDocumento texto) {
        List<Cabecalho> cabecalhos = new ArrayList<>();
        List<Integer> fronteiras = new ArrayList<>();
        texto.secoes().forEach(s -> fronteiras.add(s.inicio()));

        String anterior = "";
        boolean anteriorVazia = true;
        List<Linha> linhas = texto.linhas();
        for (int i = 0; i < linhas.size(); i++) {
            Linha linha = linhas.get(i);
            String norm = texto.linhaNormalizada(linha);
            String orig = texto.linhaOriginal(linha);
            if (norm.isBlank()) {
                anteriorVazia = true;
                continue;
            }
            int continuacao = continuacaoDeNome(texto, linhas, i, norm, orig);
            if (continuacao >= 0) {
                Matcher partido = CABECALHO_PARTIDO.matcher(norm);
                partido.find();
                int inicioNome = linha.inicio() + partido.start(1);
                cabecalhos.add(new Cabecalho(linha, inicioNome, cortarNome(texto, inicioNome, linhas.get(continuacao).fim())));
                anterior = texto.linhaOriginal(linhas.get(continuacao));
                anteriorVazia = false;
                i = continuacao;
                continue;
            }
            Matcher rotulado = CABECALHO_ROTULADO.matcher(norm);
            if (rotulado.find()) {
                String palavra = rotulado.group(1);
                int inicioNome = linha.inicio() + rotulado.start(2);
                int fimNome = cortarNome(texto, inicioNome, linha.fim());
                String nomeNorm = texto.normalizado().substring(inicioNome, fimNome);
                boolean ehEspecialidade = palavra.startsWith("especialidade") || palavra.startsWith("area") || palavra.startsWith("perfil");
                Cabecalho ultimo = cabecalhos.isEmpty() ? null : cabecalhos.get(cabecalhos.size() - 1);
                if (ehEspecialidade && ultimo != null && ultimo.inicioEspecialidade < 0
                        && linha.inicio() - ultimo.linha.fim() <= DISTANCIA_ESPECIALIDADE) {
                    ultimo.inicioEspecialidade = inicioNome;
                    ultimo.fimEspecialidade = fimNome;
                } else if (!NOME_INVALIDO.matcher(nomeNorm).find() && fimNome > inicioNome) {
                    cabecalhos.add(new Cabecalho(linha, inicioNome, fimNome));
                }
            } else if (nomeDeFicha(texto, linhas, i, orig)) {
                int inicioNome = linha.inicio() + (orig.length() - orig.stripLeading().length());
                int fimNome = cortarNome(texto, inicioNome, linha.fim());
                if (fimNome > inicioNome) {
                    cabecalhos.add(new Cabecalho(linha, inicioNome, fimNome));
                }
            } else {
                Matcher tecnico = CABECALHO_TECNICO.matcher(norm);
                if (tecnico.find() && pareceTitulo(orig, tecnico, anterior, anteriorVazia)) {
                    int inicioNome = linha.inicio() + tecnico.start(2);
                    int fimNome = cortarNome(texto, inicioNome, linha.fim());
                    if (fimNome > inicioNome) {
                        cabecalhos.add(new Cabecalho(linha, inicioNome, fimNome));
                    }
                } else if (FRONTEIRA.matcher(norm).find() && TextoDocumento.proporcaoMaiusculas(orig) >= 0.6) {
                    fronteiras.add(linha.inicio());
                }
            }
            anterior = orig;
            anteriorVazia = false;
        }
        Collections.sort(fronteiras);
        return agrupar(texto, cabecalhos, fronteiras);
    }

    /**
     * Se a linha i é só "Técnico"/"Técnico em" e a próxima linha não vazia parece o resto do nome,
     * devolve o índice dessa próxima linha; senão -1.
     */
    private static int continuacaoDeNome(TextoDocumento texto, List<Linha> linhas, int i, String norm, String orig) {
        if (!CABECALHO_PARTIDO.matcher(norm).find()
                || !Character.isUpperCase(orig.strip().replaceFirst("^\\d+\\s+", "").charAt(0))) {
            return -1;
        }
        for (int j = i + 1; j < linhas.size() && j <= i + 2; j++) {
            String proxima = texto.linhaOriginal(linhas.get(j));
            if (!proxima.isBlank()) {
                return CONTINUACAO_NOME.matcher(proxima).find() ? j : -1;
            }
        }
        return -1;
    }

    /** Linha curta, iniciada em maiúscula, sem pontuação final, e a próxima linha não vazia abre a ficha de um cargo. */
    private static boolean nomeDeFicha(TextoDocumento texto, List<Linha> linhas, int i, String orig) {
        String nome = orig.strip();
        if (!NOME_DE_FICHA.matcher(nome).find() || ".,;".indexOf(nome.charAt(nome.length() - 1)) >= 0
                || nome.split("\\s+").length > 10 || ROTULO_DE_FICHA.matcher(texto.linhaNormalizada(linhas.get(i))).find()) {
            return false;
        }
        for (int j = i + 1; j < linhas.size() && j <= i + 2; j++) {
            String proxima = texto.linhaNormalizada(linhas.get(j));
            if (!proxima.isBlank()) {
                return ROTULO_DE_FICHA.matcher(proxima).find();
            }
        }
        return false;
    }

    private static boolean pareceTitulo(String orig, Matcher m, String anterior, boolean anteriorVazia) {
        String nomeOriginal = orig.substring(m.start(2));
        if (!Character.isUpperCase(nomeOriginal.charAt(0))) {
            return false;
        }
        if (FRASE.matcher(Normalizador.normalizar(nomeOriginal)).find()) {
            return false;
        }
        if (TextoDocumento.proporcaoMaiusculas(orig) >= 0.6 || m.group(1) != null) {
            return true;
        }
        String ant = anterior.strip();
        // linha anterior só com o código da tabela ("102") também inicia cargo
        return anteriorVazia || ant.isEmpty() || ".:;!?)".indexOf(ant.charAt(ant.length() - 1)) >= 0
                || ant.matches("\\d{1,4}");
    }

    private static int cortarNome(TextoDocumento texto, int inicio, int fimLinha) {
        String norm = texto.normalizado().substring(inicio, fimLinha);
        int fim = fimLinha;
        Matcher corte = CORTE_NOME.matcher(norm);
        if (corte.find() && corte.start() > 5) {
            fim = inicio + corte.start();
        }
        String original = texto.original();
        while (fim > inicio && " \t-–:;,.(".indexOf(original.charAt(fim - 1)) >= 0) {
            fim--;
        }
        if (fim - inicio > 150) {
            int espaco = original.lastIndexOf(' ', inicio + 150);
            fim = espaco > inicio ? espaco : inicio + 150;
        }
        return fim;
    }

    private static List<CargoDetectado> agrupar(TextoDocumento texto, List<Cabecalho> cabecalhos, List<Integer> fronteiras) {
        Map<String, List<Cabecalho>> porChave = new LinkedHashMap<>();
        Map<Cabecalho, CargoDetectado.Bloco> blocos = new LinkedHashMap<>();
        for (int i = 0; i < cabecalhos.size(); i++) {
            Cabecalho c = cabecalhos.get(i);
            int fim = Math.min(texto.original().length(), c.linha.inicio() + MAX_BLOCO);
            if (i + 1 < cabecalhos.size()) {
                fim = Math.min(fim, cabecalhos.get(i + 1).linha.inicio());
            }
            for (int f : fronteiras) {
                if (f > c.linha.inicio()) {
                    fim = Math.min(fim, f);
                    break;
                }
            }
            int inicioConteudo = Math.max(c.fimNome, c.fimEspecialidade);
            blocos.put(c, new CargoDetectado.Bloco(c.linha.inicio(), inicioConteudo, Math.max(fim, inicioConteudo),
                    texto.emSecaoExcluida(c.linha.inicio())));
            porChave.computeIfAbsent(chave(texto, c), k -> new ArrayList<>()).add(c);
        }
        juntarPorPrefixo(porChave);

        List<CargoDetectado> cargos = new ArrayList<>();
        for (List<Cabecalho> grupo : porChave.values()) {
            // Cargo que só aparece em conteúdo programático/atribuições é referência, não cargo com requisito.
            if (grupo.stream().allMatch(c -> blocos.get(c).excluido())) {
                continue;
            }
            Cabecalho principal = grupo.stream().filter(c -> !blocos.get(c).excluido()).findFirst().orElseThrow();
            String nome = TextoDocumento.compactar(texto.original().substring(principal.inicioNome, principal.fimNome));
            String especialidade = null;
            int inicioEspecialidade = -1;
            if (principal.inicioEspecialidade >= 0) {
                especialidade = TextoDocumento.compactar(
                        texto.original().substring(principal.inicioEspecialidade, principal.fimEspecialidade));
                inicioEspecialidade = principal.inicioEspecialidade;
            } else {
                Matcher sep = SEPARADOR_ESPECIALIDADE.matcher(texto.normalizado().substring(principal.inicioNome, principal.fimNome));
                if (sep.find()) {
                    inicioEspecialidade = principal.inicioNome + sep.end();
                    especialidade = TextoDocumento.compactar(texto.original().substring(inicioEspecialidade, principal.fimNome));
                }
            }
            cargos.add(new CargoDetectado(nome, principal.inicioNome, principal.fimNome, especialidade, inicioEspecialidade,
                    grupo.stream().map(blocos::get).toList()));
        }
        return cargos;
    }

    /**
     * Linhas de tabela costumam trazer o nome com a lotação colada ("Técnico Audiovisual Duque de Caxias").
     * Uma chave que começa com outra chave (de pelo menos duas palavras) entra no grupo da mais curta —
     * só quando a curta é prefixo de uma única chave. Se for prefixo de várias ("técnico de laboratório"
     * → eletrotécnica, química...), é nome de família de cargos e nada é juntado.
     */
    private static void juntarPorPrefixo(Map<String, List<Cabecalho>> porChave) {
        List<String> chaves = new ArrayList<>(porChave.keySet());
        for (String base : chaves) {
            if (!base.contains(" ")) {
                continue;
            }
            List<String> longas = chaves.stream().filter(c -> c.startsWith(base + " ")).toList();
            if (longas.size() == 1 && porChave.containsKey(longas.get(0))) {
                porChave.get(base).addAll(porChave.remove(longas.get(0)));
            }
        }
    }

    private static String chave(TextoDocumento texto, Cabecalho c) {
        String nome = texto.normalizado().substring(c.inicioNome, c.fimNome);
        if (c.inicioEspecialidade >= 0) {
            nome += " " + texto.normalizado().substring(c.inicioEspecialidade, c.fimEspecialidade);
        }
        return Normalizador.chave(nome).replaceFirst("^(?:cod(?:igo)? )?\\d+ ", "");
    }
}
