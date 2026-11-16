package br.gov.crtrj.concursos.motor.alteracoes;

import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.evidencia.VerificadorEvidencias;
import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;
import br.gov.crtrj.concursos.motor.extracao.ExtratorConcurso;
import br.gov.crtrj.concursos.motor.extracao.ParserValores;
import br.gov.crtrj.concursos.motor.leitor.DocumentoLido;
import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;
import br.gov.crtrj.concursos.motor.texto.LimpadorTexto;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static br.gov.crtrj.concursos.motor.texto.Padroes.compilar;

/**
 * Lê um documento posterior ao edital (retificação, errata, comunicado, aviso de prorrogação...) e
 * aponta o que ele muda — sempre com o trecho e a página. Não decide nada: cada alteração vira uma
 * proposta que o revisor aplica ou descarta.
 */
public final class AnalisadorAlteracoes {

    public enum Tipo {
        /** Novo fim do período de inscrição. */
        PRAZO_INSCRICAO,
        /** Nova data da prova (objetiva). */
        DATA_PROVA,
        /** Concurso suspenso ou cancelado. */
        SITUACAO,
        /** Par "onde se lê / leia-se": o revisor lê e decide se afeta algum cargo publicado. */
        TRECHO_ALTERADO,
        /** "O subitem 2.2 passa a ter a seguinte redação: ..." (antes = a frase que anuncia a mudança). */
        NOVA_REDACAO
    }

    /**
     * @param valor PRAZO_INSCRICAO/DATA_PROVA: data ISO; SITUACAO: SUSPENSO ou CANCELADO; TRECHO_ALTERADO: o novo texto
     * @param antes TRECHO_ALTERADO: o texto substituído ("onde se lê"); nos demais, null
     */
    public record Alteracao(Tipo tipo, String valor, String antes, Evidencia evidencia) {
    }

    /**
     * @param titulo        primeira linha que identifica o documento ("EDITAL DE RETIFICAÇÃO Nº 02/2026"), ou null
     * @param cargosCitados cargos procurados que aparecem no documento (a retificação pode mexer neles)
     */
    public record Resultado(String titulo, int paginas, List<Integer> paginasSemTexto, List<Alteracao> alteracoes,
                            List<CargoCitado> cargosCitados) {
    }

    /** @param nome como foi procurado (nome do cargo gravado) */
    public record CargoCitado(String nome, Evidencia evidencia) {
    }

    private static final Pattern PRORROGACAO = compilar("prorroga[a-z]*|reabert[a-z]*|reabre");
    private static final Pattern INSCRICAO = compilar("inscri[a-z]*");
    private static final Pattern ISENCAO = compilar("isencao");
    private static final Pattern ADIAMENTO = compilar("adia[a-z]*|remarca[a-z]*|nova data|alterad[ao]s? a data"
            + "|alteracao da data|transferid[ao]s?");
    private static final Pattern PROVA = compilar("(?<![a-z])provas?(?![a-z])");
    private static final Pattern SUSPENSAO = compilar("(?<![a-z])suspen[a-z]*");
    private static final Pattern CANCELAMENTO = compilar("(?<![a-z])(?:cancela[a-z]*|anula[a-z]*|revoga[a-z]*|torna sem efeito"
            + "|tornad[ao] sem efeito)");
    private static final Pattern OBJETO_DO_CONCURSO = compilar("concurso|certame|processo seletivo|(?<![a-z])edital");
    /** Cláusulas hipotéticas, comuns no texto padrão ("o concurso poderá ser cancelado..."). */
    private static final Pattern HIPOTESE = compilar("podera|poderao|pode ser|podem ser|caso |hipotese|eventua[a-z]*"
            + "|se houver|a criterio|reserva-se");
    private static final Pattern ATE = compilar("(?<![a-z])ate(?![a-z])");
    private static final Pattern PARA = compilar("(?<![a-z])para(?: o dia| a data| o)?(?![a-z])");
    private static final Pattern ONDE_SE_LE = compilar("onde se (?:le|lia)(?![a-z])");
    private static final Pattern LEIA_SE = compilar("leia-se|leia se");
    private static final Pattern NOVA_REDACAO = compilar("passam? a (?:ser|ter|vigorar)(?: com)?(?: a| o| as| os)? seguintes?"
            + "(?: redacao| forma| teor| texto)?");
    /** Título do documento: primeiro o que diz o que ele é; "edital" sozinho costuma ser o cabeçalho repetido. */
    private static final Pattern TITULO_ESPECIFICO = compilar("retifica|errata|comunicado|aviso|aditivo|prorroga|adiamento"
            + "|suspens|cancela|consolidad");
    private static final Pattern TITULO_GENERICO = compilar("edital");
    private static final int LINHAS_DO_TITULO = 60;
    /** Início de linha com item numerado: "2.", "3.1", "4)" (uma data "14/08/2026" não conta). */
    private static final Pattern PROXIMO_ITEM = Pattern.compile("\\n\\s*\\d{1,2}(?:\\.\\d{1,2})*[.)\\-–]?\\s");
    private static final int ALCANCE_DATA = 80;
    private static final int MAX_TRECHO_ALTERADO = 1500;

    private final LeitorPdf leitor = new LeitorPdf();
    private final ExtratorConcurso extratorConcurso = new ExtratorConcurso();
    private final VerificadorEvidencias verificador = new VerificadorEvidencias();

    /** @param cargos nomes de cargos a procurar no documento (os já publicados do concurso) */
    public Resultado analisarPdf(byte[] pdf, Collection<String> cargos) throws IOException {
        return analisar(leitor.ler(pdf), cargos);
    }

    public Resultado analisar(DocumentoLido lido) {
        return analisar(lido, List.of());
    }

    public Resultado analisar(DocumentoLido lido, Collection<String> cargos) {
        TextoDocumento texto = TextoDocumento.de(LimpadorTexto.limpar(lido.paginasBrutas()));
        // chave = tipo + valor: a mesma mudança citada duas vezes vira uma proposta só
        Map<String, Alteracao> achadas = new LinkedHashMap<>();
        for (Alteracao a : ondeSeLe(texto)) {
            adicionar(achadas, texto, a);
        }
        novaRedacao(texto).forEach(a -> adicionar(achadas, texto, a));
        prorrogacoes(texto).forEach(a -> adicionar(achadas, texto, a));
        adiamentos(texto).forEach(a -> adicionar(achadas, texto, a));
        situacao(texto).forEach(a -> adicionar(achadas, texto, a));

        // Documento que republica o cronograma sem dizer "prorroga": usa as datas dele, se nada mais específico apareceu.
        DadosConcurso k = extratorConcurso.extrair(texto);
        if (achadas.values().stream().noneMatch(a -> a.tipo() == Tipo.PRAZO_INSCRICAO) && k.inscricaoFim().presente()) {
            adicionar(achadas, texto, new Alteracao(Tipo.PRAZO_INSCRICAO, k.inscricaoFim().valor().toString(), null,
                    k.inscricaoFim().evidencia()));
        }
        if (achadas.values().stream().noneMatch(a -> a.tipo() == Tipo.DATA_PROVA) && k.dataProva().presente()) {
            adicionar(achadas, texto, new Alteracao(Tipo.DATA_PROVA, k.dataProva().valor().toString(), null,
                    k.dataProva().evidencia()));
        }
        return new Resultado(titulo(texto), texto.totalPaginas(), lido.paginasSemTexto(), List.copyOf(achadas.values()),
                citados(texto, cargos));
    }

    /** Primeira menção de cada cargo, com fronteira de palavra e tolerando quebra de linha no meio do nome. */
    private List<CargoCitado> citados(TextoDocumento texto, Collection<String> cargos) {
        List<CargoCitado> lista = new ArrayList<>();
        for (String nome : cargos) {
            String[] palavras = Normalizador.normalizar(nome).strip().split("\\s+");
            if (palavras.length == 0 || palavras[0].isEmpty()) {
                continue;
            }
            StringBuilder regex = new StringBuilder("(?<![a-z])");
            for (int i = 0; i < palavras.length; i++) {
                regex.append(i == 0 ? "" : "\\s+").append(Pattern.quote(palavras[i]));
            }
            Matcher m = Pattern.compile(regex.append("(?![a-z])").toString()).matcher(texto.normalizado());
            if (m.find()) {
                Evidencia ev = verificador.verificar(texto, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
                if (ev != null) {
                    lista.add(new CargoCitado(nome, ev));
                }
            }
        }
        return lista;
    }

    private void adicionar(Map<String, Alteracao> achadas, TextoDocumento texto, Alteracao a) {
        Evidencia verificada = verificador.verificar(texto, a.evidencia());
        if (verificada != null) {
            achadas.putIfAbsent(a.tipo() + "|" + a.valor(), new Alteracao(a.tipo(), a.valor(), a.antes(), verificada));
        }
    }

    // ------------------------------------------------------------------ onde se lê / leia-se

    private static List<Alteracao> ondeSeLe(TextoDocumento texto) {
        String norm = texto.normalizado();
        List<Alteracao> lista = new ArrayList<>();
        Matcher onde = ONDE_SE_LE.matcher(norm);
        int de = 0;
        while (onde.find(de)) {
            Matcher leia = LEIA_SE.matcher(norm).region(onde.end(), Math.min(norm.length(), onde.end() + MAX_TRECHO_ALTERADO));
            if (!leia.find()) {
                de = onde.end();
                continue;
            }
            int limite = Math.min(norm.length(), leia.end() + MAX_TRECHO_ALTERADO);
            Matcher proximo = ONDE_SE_LE.matcher(norm).region(leia.end(), limite);
            int fimDepois = fimDoNovoTexto(norm, leia.end(), proximo.find() ? proximo.start() : limite);
            String antes = limpo(texto.original().substring(onde.end(), leia.start()));
            String depois = limpo(texto.original().substring(leia.end(), fimDepois));
            Evidencia ev = texto.evidencia(leia.start(), Math.min(fimDepois, leia.end() + 200), MetodoExtracao.REGEX);
            lista.add(new Alteracao(Tipo.TRECHO_ALTERADO, depois, antes, ev));
            lista.addAll(datasNoNovoTexto(texto, leia.end(), fimDepois));
            de = fimDepois;
        }
        return lista;
    }

    /** "1 - A remuneração dos cargos ... passa a ser a seguinte:" seguido do texto (ou tabela) novo. */
    private static List<Alteracao> novaRedacao(TextoDocumento texto) {
        String norm = texto.normalizado();
        List<Alteracao> lista = new ArrayList<>();
        Matcher m = NOVA_REDACAO.matcher(norm);
        int de = 0;
        while (m.find(de)) {
            int inicioFrase = texto.sentenca(m.start(), m.end())[0];
            int fim = fimDoNovoTexto(norm, m.end(), Math.min(norm.length(), m.end() + MAX_TRECHO_ALTERADO));
            String anuncio = limpo(texto.original().substring(inicioFrase, m.end()));
            String novo = limpo(texto.original().substring(m.end(), fim));
            if (!novo.isBlank()) {
                lista.add(new Alteracao(Tipo.NOVA_REDACAO, novo, anuncio,
                        texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX)));
                lista.addAll(datasNoNovoTexto(texto, m.end(), fim));
            }
            de = Math.max(fim, m.end());
        }
        return lista;
    }

    /**
     * Onde termina o texto novo que começa em {@code inicio}: no próximo item numerado ("2 - O subitem...",
     * procurado a partir da 2ª linha), em duas linhas em branco seguidas, ou em {@code limite}.
     */
    private static int fimDoNovoTexto(String norm, int inicio, int limite) {
        int fim = limite;
        int conteudo = inicio;
        while (conteudo < fim && Character.isWhitespace(norm.charAt(conteudo))) {
            conteudo++;
        }
        int brancas = norm.indexOf("\n\n\n", conteudo);
        if (brancas > 0 && brancas < fim) {
            fim = brancas;
        }
        int fimPrimeiraLinha = norm.indexOf('\n', conteudo);
        if (fimPrimeiraLinha > 0 && fimPrimeiraLinha < fim) {
            Matcher item = PROXIMO_ITEM.matcher(norm).region(fimPrimeiraLinha, fim);
            if (item.find()) {
                fim = item.start();
            }
        }
        return fim;
    }

    /** O texto novo fala de inscrição ou de prova com data: também vira proposta de data. */
    private static List<Alteracao> datasNoNovoTexto(TextoDocumento texto, int inicio, int fim) {
        String norm = texto.normalizado();
        String trecho = norm.substring(inicio, fim);
        List<ParserValores.DataEncontrada> datas = ParserValores.datas(norm, inicio, fim);
        if (datas.isEmpty()) {
            return List.of();
        }
        if (INSCRICAO.matcher(trecho).find() && !ISENCAO.matcher(trecho).find()) {
            ParserValores.DataEncontrada ultima = datas.getLast();
            return List.of(new Alteracao(Tipo.PRAZO_INSCRICAO, ultima.data().toString(), null,
                    texto.evidencia(inicio, ultima.fim(), MetodoExtracao.REGEX)));
        }
        if (PROVA.matcher(trecho).find()) {
            ParserValores.DataEncontrada primeira = datas.getFirst();
            return List.of(new Alteracao(Tipo.DATA_PROVA, primeira.data().toString(), null,
                    texto.evidencia(inicio, primeira.fim(), MetodoExtracao.REGEX)));
        }
        return List.of();
    }

    /** Mantém as quebras de linha (tabelas de cargos continuam legíveis), sem linhas vazias nem espaços sobrando. */
    private static String limpo(String s) {
        String semVazias = s.lines().map(l -> l.strip().replaceAll("\\s+", " ")).filter(l -> !l.isEmpty())
                .collect(Collectors.joining("\n"));
        return semVazias.replaceAll("^[\\s:\"“”]+|[\\s\"“”]+$", "");
    }

    // ------------------------------------------------------------------ prorrogação e adiamento

    private static List<Alteracao> prorrogacoes(TextoDocumento texto) {
        List<Alteracao> lista = new ArrayList<>();
        Matcher m = PRORROGACAO.matcher(texto.normalizado());
        while (m.find()) {
            int[] janela = janela(texto, m.start(), m.end());
            String trecho = texto.normalizado().substring(janela[0], janela[1]);
            if (!INSCRICAO.matcher(trecho).find() || HIPOTESE.matcher(trecho).find()) {
                continue;
            }
            ParserValores.DataEncontrada d = novaData(texto.normalizado(), m.start(), janela[1]);
            if (d != null) {
                lista.add(new Alteracao(Tipo.PRAZO_INSCRICAO, d.data().toString(), null,
                        texto.evidencia(m.start(), d.fim(), MetodoExtracao.REGEX)));
            }
        }
        return maisTardia(lista);
    }

    private static List<Alteracao> adiamentos(TextoDocumento texto) {
        List<Alteracao> lista = new ArrayList<>();
        Matcher m = ADIAMENTO.matcher(texto.normalizado());
        while (m.find()) {
            int[] janela = janela(texto, m.start(), m.end());
            String trecho = texto.normalizado().substring(janela[0], janela[1]);
            if (!PROVA.matcher(trecho).find() || HIPOTESE.matcher(trecho).find()) {
                continue;
            }
            ParserValores.DataEncontrada d = novaData(texto.normalizado(), m.start(), janela[1]);
            if (d != null) {
                lista.add(new Alteracao(Tipo.DATA_PROVA, d.data().toString(), null,
                        texto.evidencia(m.start(), d.fim(), MetodoExtracao.REGEX)));
            }
        }
        return maisTardia(lista);
    }

    /**
     * Prorrogação e adiamento empurram a data para frente; o documento costuma citar também a data antiga
     * ("previstas até 17/09/2026, ficam prorrogadas até 24/09/2026"): fica a mais tardia.
     */
    private static List<Alteracao> maisTardia(List<Alteracao> lista) {
        return lista.stream().max(Comparator.comparing(Alteracao::valor)).map(List::of).orElse(List.of());
    }

    /** A frase do achado e a seguinte ("Fica prorrogado o prazo. As inscrições vão até 24/09/2026."). */
    private static int[] janela(TextoDocumento texto, int inicio, int fim) {
        int[] frase = texto.sentenca(inicio, fim);
        int[] seguinte = frase[1] < texto.normalizado().length() ? texto.sentenca(frase[1], frase[1]) : frase;
        return new int[]{frase[0], Math.max(frase[1], seguinte[1])};
    }

    /**
     * A data nova, depois do achado: a primeira após "até" ("prorrogadas até 24/09/2026, pagamento até 25/09"),
     * senão após "para" ("adiada para o dia 06/12/2026"), senão a última da frase.
     */
    private static ParserValores.DataEncontrada novaData(String norm, int de, int ate) {
        for (Pattern marcador : List.of(ATE, PARA)) {
            Matcher m = marcador.matcher(norm).region(de, ate);
            while (m.find()) {
                List<ParserValores.DataEncontrada> datas = ParserValores.datas(norm, m.end(), Math.min(ate, m.end() + ALCANCE_DATA));
                if (!datas.isEmpty()) {
                    return datas.getFirst();
                }
            }
        }
        List<ParserValores.DataEncontrada> datas = ParserValores.datas(norm, de, ate);
        return datas.isEmpty() ? null : datas.getLast();
    }

    // ------------------------------------------------------------------ suspensão / cancelamento

    private static List<Alteracao> situacao(TextoDocumento texto) {
        List<Alteracao> lista = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : List.of(Map.entry("SUSPENSO", SUSPENSAO), Map.entry("CANCELADO", CANCELAMENTO))) {
            Matcher m = e.getValue().matcher(texto.normalizado());
            while (m.find()) {
                int[] frase = texto.sentenca(m.start(), m.end());
                String trecho = texto.normalizado().substring(frase[0], frase[1]);
                if (OBJETO_DO_CONCURSO.matcher(trecho).find() && !HIPOTESE.matcher(trecho).find()) {
                    lista.add(new Alteracao(Tipo.SITUACAO, e.getKey(), null,
                            texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX)));
                    break;
                }
            }
        }
        return lista;
    }

    // ------------------------------------------------------------------ título

    /** "RETIFICAÇÃO I AO EDITAL Nº 01/2026" vence o cabeçalho "EDITAL Nº 01/2026" repetido em toda página. */
    private static String titulo(TextoDocumento texto) {
        List<TextoDocumento.Linha> inicio = texto.linhas().stream().limit(LINHAS_DO_TITULO).toList();
        for (Pattern padrao : List.of(TITULO_ESPECIFICO, TITULO_GENERICO)) {
            for (TextoDocumento.Linha l : inicio) {
                String norm = texto.linhaNormalizada(l);
                if (padrao.matcher(norm).find() && norm.strip().length() >= 8 && norm.strip().length() <= 160) {
                    String original = TextoDocumento.compactar(texto.linhaOriginal(l));
                    return original.length() > 200 ? original.substring(0, 200) : original;
                }
            }
        }
        return null;
    }
}
