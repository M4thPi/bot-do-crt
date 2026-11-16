package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento.Linha;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.gov.crtrj.concursos.motor.texto.Padroes.compilar;

/** Dados que valem para o concurso inteiro: órgão, banca, inscrições, taxa, prova. */
public final class ExtratorConcurso {

    private static final Pattern NUMERO_EDITAL = compilar(
            // "Edital nº 01/2026", "Edital de Concurso Público nº 01/2026", "Edital de Abertura 01/2026"
            "edital\\s*(?:de abertura\\s*)?(?:(?:de )?concurso publico\\s*)?(?:n[o°.]*|numero)?\\s*[:.]?\\s*"
                    + "(\\d{1,4}\\s*/\\s*(?:[a-z][a-z-]{1,15}\\s*/\\s*)?\\d{2,4})");
    /** Linha curta que começa com o tipo de órgão, opcionalmente precedido da sigla ("UFF – UNIVERSIDADE ..."). */
    private static final Pattern ORGAO = compilar(
            "^\\s*(?:[a-z]{2,10}\\s*[-–]\\s*)?(prefeitura|camara municipal|governo d|estado d[eoa] |secretaria|instituto|universidade|companhia|empresa|fundacao"
                    + "|tribunal|ministerio|conselho|servico|agencia|banco|petroleo|centro federal|municipio d|assembleia|defensoria"
                    + "|procuradoria|autarquia|departamento|superintendencia|comissao nacional|industrias nucleares|eletrobras|caixa"
                    + "|correios|marinha|comando)");
    private static final Pattern ESFERA_MUNICIPAL = compilar("prefeitura|camara municipal|municipio d|secretaria municipal");
    private static final Pattern ESFERA_ESTADUAL = compilar(
            "governo do estado|secretaria de estado|estado d[eoa] |assembleia legislativa|tribunal de justica|defensoria publica do estado");
    private static final Pattern ESFERA_FEDERAL = compilar(
            "federal|ministerio|(?<![a-z])uniao(?![a-z])|tribunal regional|comando d[oa]|marinha do brasil|exercito|aeronautica");
    private static final Pattern MUNICIPIO = compilar(
            "(?:prefeitura(?: municipal)?|municipio|camara municipal) d[eoa]s?\\s+(\\p{L}[^\\n,;/()–-]{1,40})");
    private static final Pattern URL = compilar("(?:https?://|www\\.)[\\w./\\-?=&%#~:+]+");

    private static final Map<String, String> ESTADOS = new LinkedHashMap<>();
    private static final Pattern SIGLA_UF = compilar(
            "(?:[-/–]\\s*|\\()(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)(?![A-Za-z])");

    static {
        // Nomes compostos antes dos simples ("mato grosso do sul" antes de "mato grosso").
        ESTADOS.put("mato grosso do sul", "MS");
        ESTADOS.put("rio grande do norte", "RN");
        ESTADOS.put("rio grande do sul", "RS");
        ESTADOS.put("rio de janeiro", "RJ");
        ESTADOS.put("mato grosso", "MT");
        ESTADOS.put("distrito federal", "DF");
        ESTADOS.put("espirito santo", "ES");
        ESTADOS.put("minas gerais", "MG");
        ESTADOS.put("santa catarina", "SC");
        ESTADOS.put("sao paulo", "SP");
        ESTADOS.put("acre", "AC");
        ESTADOS.put("alagoas", "AL");
        ESTADOS.put("amapa", "AP");
        ESTADOS.put("amazonas", "AM");
        ESTADOS.put("bahia", "BA");
        ESTADOS.put("ceara", "CE");
        ESTADOS.put("goias", "GO");
        ESTADOS.put("maranhao", "MA");
        ESTADOS.put("paraiba", "PB");
        ESTADOS.put("parana", "PR");
        ESTADOS.put("para", "PA");
        ESTADOS.put("pernambuco", "PE");
        ESTADOS.put("piaui", "PI");
        ESTADOS.put("rondonia", "RO");
        ESTADOS.put("roraima", "RR");
        ESTADOS.put("sergipe", "SE");
        ESTADOS.put("tocantins", "TO");
    }

    private record Banca(String nome, Pattern padrao) {
    }

    private static final List<Banca> BANCAS = List.of(
            new Banca("Fundação Getulio Vargas (FGV)", compilar("fundacao getulio vargas|(?<![a-z])fgv(?![a-z])")),
            new Banca("Fundação Cesgranrio", compilar("cesgranrio")),
            new Banca("Cebraspe", compilar("cebraspe|(?<![a-z])cespe(?![a-z])")),
            new Banca("IBFC", compilar("(?<![a-z])ibfc(?![a-z])|instituto brasileiro de formacao e capacitacao")),
            new Banca("Instituto AOCP", compilar("(?<![a-z])aocp(?![a-z])")),
            new Banca("Instituto Selecon", compilar("selecon")),
            new Banca("IBAM", compilar("(?<![a-z])ibam(?![a-z])|instituto brasileiro de administracao municipal")),
            new Banca("IBADE", compilar("(?<![a-z])ibade(?![a-z])")),
            new Banca("Consulplan", compilar("consulplan")),
            new Banca("Idecan", compilar("idecan")),
            new Banca("Fundação Carlos Chagas (FCC)", compilar("fundacao carlos chagas|(?<![a-z])fcc(?![a-z])")),
            new Banca("Vunesp", compilar("vunesp")),
            new Banca("Fundação CEPERJ", compilar("ceperj")),
            new Banca("Instituto Access", compilar("instituto access")),
            new Banca("Quadrix", compilar("quadrix")),
            new Banca("Fundatec", compilar("fundatec")),
            new Banca("Iades", compilar("(?<![a-z])iades(?![a-z])")),
            new Banca("Fundação Dom Cintra", compilar("dom cintra")),
            new Banca("Coseac/UFF", compilar("coseac")),
            new Banca("IDIB", compilar("(?<![a-z])idib(?![a-z])")),
            new Banca("Objetiva Concursos", compilar("objetiva concursos")),
            new Banca("Fundep", compilar("(?<![a-z])fundep(?![a-z])")),
            new Banca("Fepese", compilar("fepese")));
    private static final Pattern PISTA_BANCA = compilar("organiza|execu|realiza|responsab|banca|coordena");
    private static final Pattern BANCA_GENERICA = compilar(
            "(?:organizad[oa]|executad[oa]|realizad[oa])s?\\s+(?:e\\s+\\p{L}+\\s+)?(?:pel[oa]|sob a responsabilidade d[oa])\\s+([^,.;\\n]{3,80})");

    private static final Pattern INSCRICAO = compilar("inscric");
    private static final Pattern DESVIO_INSCRICAO = compilar(
            "isencao|recurso|homologa|resultado|deferid|indeferid|pagamento|boleto|vencimento|cartao|confirmacao|local|provas?(?![a-z])|laudo|atendimento");
    private static final Pattern CONECTOR_PERIODO = compilar("(?<![a-z])(?:a|ate|e|ao)(?![a-z])");
    private static final Pattern TAXA = compilar("taxa (?:de )?inscricao|valor da (?:taxa de )?inscricao|taxa de participacao");
    private static final Pattern ISENCAO = compilar("isencao");
    private static final Pattern PISTA_PROVA = compilar(
            "provas? objetivas?|aplicacao das provas|as provas (?:serao|estao) (?:realizadas|previstas|aplicadas)"
                    + "|data (?:provavel|prevista) (?:da|de aplicacao da|das|para (?:a )?realizacao da)s? provas?|realizacao das provas");
    /** Entre a pista e a data: outro evento do cronograma, ou citação de lei ("…prova objetiva, na forma da Lei nº …, de 1º de outubro de 2003"). */
    private static final Pattern DESVIO_PROVA = compilar("resultado|gabarito|recurso|divulga|convoca|local|cartao|confirmacao"
            + "|(?<![a-z])(?:lei|decreto|art|artigo)(?![a-z])");
    /** Cronograma em tabela: a data vem antes do evento ("22/11/26 Realização das Provas"). */
    private static final Pattern SO_SEPARADORES = Pattern.compile("[\\s\\-–—:|]*");
    private static final int ALCANCE_DATA_ANTES = 25;

    private static final List<Map.Entry<String, Pattern>> ETAPAS = List.of(
            Map.entry("Prova objetiva", compilar("provas? objetivas?")),
            Map.entry("Prova discursiva", compilar("provas? discursivas?|(?<![a-z])redacao(?![a-z])")),
            Map.entry("Prova prática", compilar("provas? praticas?")),
            Map.entry("Prova de títulos", compilar("provas? de titulos|avaliacao de titulos")),
            Map.entry("Teste de aptidão física", compilar("teste de aptidao fisica|(?<![a-z])(?:taf|tfa)(?![a-z])|teste fisico")),
            Map.entry("Avaliação psicológica", compilar("avaliacao psicologica|exame psicotecnico")),
            Map.entry("Curso de formação", compilar("curso de formacao")),
            Map.entry("Investigação social", compilar("investigacao social")));

    private record Periodo(Campo<LocalDate> inicio, Campo<LocalDate> fim) {
        static Periodo vazio() {
            return new Periodo(Campo.naoEncontrado(), Campo.naoEncontrado());
        }
    }

    private record Isencao(Campo<String> prevista, Campo<LocalDate> fim) {
    }

    public DadosConcurso extrair(TextoDocumento texto) {
        Campo<String> orgao = orgao(texto);
        Periodo periodo = periodoInscricao(texto);
        Isencao isencao = isencao(texto);
        return new DadosConcurso(orgao, numeroEdital(texto), banca(texto), esfera(orgao), uf(texto), municipio(texto),
                periodo.inicio(), periodo.fim(), taxas(texto), isencao.prevista(), isencao.fim(), siteInscricao(texto),
                dataProva(texto, periodo), etapas(texto));
    }

    private static Campo<String> numeroEdital(TextoDocumento texto) {
        Matcher m = NUMERO_EDITAL.matcher(texto.normalizado());
        if (m.find()) {
            String numero = texto.original().substring(m.start(1), m.end(1)).replaceAll("\\s", "");
            return Campo.encontrado(numero, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> orgao(TextoDocumento texto) {
        List<Linha> primeiras = new ArrayList<>();
        int fimPagina1 = texto.fimDaPagina(1);
        for (Linha l : texto.linhas()) {
            if (l.inicio() >= fimPagina1 || primeiras.size() >= 25) {
                break;
            }
            if (!texto.linhaOriginal(l).isBlank()) {
                primeiras.add(l);
            }
        }
        for (boolean exigirDestaque : new boolean[]{true, false}) {
            for (Linha l : primeiras) {
                String orig = texto.linhaOriginal(l);
                if (orig.strip().length() <= 100 && ORGAO.matcher(texto.linhaNormalizada(l)).find()
                        && (!exigirDestaque || TextoDocumento.proporcaoMaiusculas(orig) >= 0.6)) {
                    String nome = TextoDocumento.compactar(orig).replaceAll("[\\s.,;:–-]+$", "");
                    return Campo.encontrado(nome, texto.evidencia(l.inicio(), l.fim(), MetodoExtracao.REGEX));
                }
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> esfera(Campo<String> orgao) {
        if (!orgao.presente()) {
            return Campo.naoEncontrado();
        }
        String norm = Normalizador.normalizar(orgao.valor());
        String esfera = ESFERA_MUNICIPAL.matcher(norm).find() ? "Municipal"
                : ESFERA_ESTADUAL.matcher(norm).find() ? "Estadual"
                : ESFERA_FEDERAL.matcher(norm).find() ? "Federal"
                : null;
        return esfera == null ? Campo.naoEncontrado() : Campo.encontrado(esfera, orgao.evidencia());
    }

    private static Campo<String> uf(TextoDocumento texto) {
        int limite = texto.fimDaPagina(Math.min(3, texto.totalPaginas()));
        String norm = texto.normalizado();
        int melhor = Integer.MAX_VALUE;
        String sigla = null;
        int fimMelhor = -1;
        for (Map.Entry<String, String> e : ESTADOS.entrySet()) {
            Matcher m = compilar("estado d[eoa] " + e.getKey() + "(?![a-z])").matcher(norm).region(0, limite);
            if (m.find() && m.start() < melhor) {
                melhor = m.start();
                fimMelhor = m.end();
                sigla = e.getValue();
            }
        }
        if (sigla != null) {
            return Campo.encontrado(sigla, texto.evidencia(melhor, fimMelhor, MetodoExtracao.REGEX));
        }
        Matcher m = SIGLA_UF.matcher(texto.original()).region(0, Math.min(limite, texto.fimDaPagina(Math.min(2, texto.totalPaginas()))));
        if (m.find()) {
            return Campo.encontrado(m.group(1), texto.evidencia(m.start(1), m.end(1), MetodoExtracao.REGEX));
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> municipio(TextoDocumento texto) {
        int limite = texto.fimDaPagina(Math.min(2, texto.totalPaginas()));
        Matcher m = MUNICIPIO.matcher(texto.normalizado()).region(0, limite);
        while (m.find()) {
            String nome = TextoDocumento.compactar(texto.original().substring(m.start(1), m.end(1)))
                    .replaceAll("(?i)\\s+(estado|edital|torna|faz|comunica).*$", "").strip();
            if (!nome.isEmpty() && Character.isUpperCase(nome.charAt(0))) {
                return Campo.encontrado(nome, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> banca(TextoDocumento texto) {
        String norm = texto.normalizado();
        Campo<String> semPista = null;
        for (Banca banca : BANCAS) {
            Matcher m = banca.padrao().matcher(norm);
            while (m.find()) {
                String entorno = norm.substring(Math.max(0, m.start() - 200), Math.min(norm.length(), m.end() + 200));
                if (PISTA_BANCA.matcher(entorno).find()) {
                    return Campo.encontrado(banca.nome(), texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
                }
                if (semPista == null) {
                    semPista = Campo.ambiguo(banca.nome(), texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
                }
            }
        }
        if (semPista != null) {
            return semPista;
        }
        Matcher m = BANCA_GENERICA.matcher(norm);
        if (m.find()) {
            String nome = TextoDocumento.compactar(texto.original().substring(m.start(1), m.end(1)));
            return Campo.ambiguo(nome, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> siteInscricao(TextoDocumento texto) {
        String orig = texto.original();
        Matcher inscricao = INSCRICAO.matcher(texto.normalizado());
        while (inscricao.find()) {
            Matcher url = URL.matcher(orig).region(inscricao.start(), Math.min(orig.length(), inscricao.start() + 250));
            if (url.find()) {
                String endereco = url.group().replaceAll("[.,;:)]+$", "");
                return Campo.encontrado(endereco, texto.evidencia(url.start(), url.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Periodo periodoInscricao(TextoDocumento texto) {
        String norm = texto.normalizado();
        Matcher m = INSCRICAO.matcher(norm);
        while (m.find()) {
            int fimJanela = Math.min(norm.length(), m.start() + 350);
            List<ParserValores.DataEncontrada> datas = ParserValores.datas(norm, m.start(), fimJanela);
            if (datas.isEmpty() || DESVIO_INSCRICAO.matcher(norm.substring(m.end(), datas.get(0).inicio())).find()) {
                continue;
            }
            for (int i = 0; i + 1 < datas.size(); i++) {
                ParserValores.DataEncontrada a = datas.get(i);
                ParserValores.DataEncontrada b = datas.get(i + 1);
                String entre = norm.substring(a.fim(), b.inicio());
                if (entre.length() <= 60 && CONECTOR_PERIODO.matcher(entre).find() && !b.data().isBefore(a.data())) {
                    var evidencia = texto.evidencia(a.inicio(), b.fim(), MetodoExtracao.REGEX);
                    return new Periodo(Campo.encontrado(a.data(), evidencia), Campo.encontrado(b.data(), evidencia));
                }
            }
        }
        return Periodo.vazio();
    }

    private static List<DadosConcurso.Taxa> taxas(TextoDocumento texto) {
        String norm = texto.normalizado();
        List<DadosConcurso.Taxa> taxas = new ArrayList<>();
        List<Integer> vistos = new ArrayList<>();
        Matcher m = TAXA.matcher(norm);
        while (m.find() && taxas.size() < 6) {
            int fim = Math.min(norm.length(), m.end() + 300);
            for (ParserValores.ValorEncontrado v : ParserValores.valores(norm, m.start(), fim)) {
                if (vistos.contains(v.inicio())) {
                    continue;
                }
                vistos.add(v.inicio());
                String contexto = norm.substring(Math.max(0, v.inicio() - 80), v.fim());
                taxas.add(new DadosConcurso.Taxa(
                        Campo.encontrado(v.valor(), texto.evidencia(v.inicio(), v.fim(), MetodoExtracao.REGEX)), contexto));
            }
        }
        return taxas;
    }

    private static Isencao isencao(TextoDocumento texto) {
        String norm = texto.normalizado();
        Matcher m = ISENCAO.matcher(norm);
        while (m.find()) {
            int[] frase = texto.sentenca(m.start(), m.end());
            String fraseNorm = norm.substring(frase[0], frase[1]);
            if (!fraseNorm.contains("taxa") && !fraseNorm.contains("pagamento") && !fraseNorm.contains("inscric")) {
                continue;
            }
            Campo<LocalDate> fim = Campo.naoEncontrado();
            List<ParserValores.DataEncontrada> datas = ParserValores.datas(norm, m.start(), Math.min(norm.length(), m.start() + 300));
            if (datas.size() >= 2) {
                ParserValores.DataEncontrada ultima = datas.get(1);
                fim = Campo.encontrado(ultima.data(), texto.evidencia(datas.get(0).inicio(), ultima.fim(), MetodoExtracao.REGEX));
            }
            return new Isencao(Campo.encontrado("Prevista no edital", texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX)), fim);
        }
        return new Isencao(Campo.naoEncontrado(), Campo.naoEncontrado());
    }

    /**
     * A prova nunca acontece antes do fim das inscrições: uma data anterior é de outra coisa
     * (lei citada, edital anterior...) e é descartada.
     */
    private static Campo<LocalDate> dataProva(TextoDocumento texto, Periodo inscricoes) {
        LocalDate limite = inscricoes.fim().valorOu(inscricoes.inicio().valorOu(null));
        String norm = texto.normalizado();
        Matcher m = PISTA_PROVA.matcher(norm);
        while (m.find()) {
            // cronograma: "22/11/26 Realização das Provas"
            List<ParserValores.DataEncontrada> antes = ParserValores.datas(norm, Math.max(0, m.start() - ALCANCE_DATA_ANTES), m.start());
            if (!antes.isEmpty()) {
                ParserValores.DataEncontrada d = antes.getLast();
                if (SO_SEPARADORES.matcher(norm.substring(d.fim(), m.start())).matches() && naoAntesDe(d.data(), limite)) {
                    return Campo.encontrado(d.data(), texto.evidencia(d.inicio(), m.end(), MetodoExtracao.REGEX));
                }
            }
            // texto corrido: "As provas serão realizadas em 29/11/2026"
            List<ParserValores.DataEncontrada> depois = ParserValores.datas(norm, m.end(), Math.min(norm.length(), m.end() + 200));
            if (depois.isEmpty()) {
                continue;
            }
            ParserValores.DataEncontrada d = depois.getFirst();
            if (DESVIO_PROVA.matcher(norm.substring(m.end(), d.inicio())).find() || !naoAntesDe(d.data(), limite)) {
                continue;
            }
            return Campo.encontrado(d.data(), texto.evidencia(m.start(), d.fim(), MetodoExtracao.REGEX));
        }
        return Campo.naoEncontrado();
    }

    private static boolean naoAntesDe(LocalDate data, LocalDate limite) {
        return limite == null || !data.isBefore(limite);
    }

    private static List<Campo<String>> etapas(TextoDocumento texto) {
        String norm = texto.normalizado();
        List<Campo<String>> etapas = new ArrayList<>();
        for (Map.Entry<String, Pattern> etapa : ETAPAS) {
            Matcher m = etapa.getValue().matcher(norm);
            while (m.find()) {
                if (!texto.emSecaoExcluida(m.start())) {
                    etapas.add(Campo.encontrado(etapa.getKey(), texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX)));
                    break;
                }
            }
        }
        return etapas;
    }

    /** Taxa que vale para o cargo: única, ou a que cita nível técnico/médio. Várias sem pista = ambígua. */
    public static Campo<BigDecimal> taxaParaCargo(List<DadosConcurso.Taxa> taxas, String escolaridadeNormalizada) {
        if (taxas.isEmpty()) {
            return Campo.naoEncontrado();
        }
        if (taxas.size() == 1) {
            return taxas.get(0).valor();
        }
        List<String> pistas = new ArrayList<>(List.of("tecnic", "medio"));
        if (escolaridadeNormalizada != null && escolaridadeNormalizada.contains("superior")) {
            pistas = List.of("superior");
        }
        for (DadosConcurso.Taxa t : taxas) {
            String ctx = t.contexto();
            if (pistas.stream().anyMatch(ctx::contains)) {
                return t.valor();
            }
        }
        Campo<BigDecimal> primeira = taxas.get(0).valor();
        return Campo.ambiguo(primeira.valor(), primeira.evidencia());
    }
}
