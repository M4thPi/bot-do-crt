package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.texto.Padroes;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o requisito de formação de um cargo. Só considera o texto das faixas de requisito — nunca
 * o nome do cargo — para que "Técnico em Eletrotécnica" no título não vire formação exigida.
 */
public final class AnalisadorRequisitos {

    static final Pattern ROTULO_REQUISITO = Padroes.compilar(
            "(?<![a-z])(requisitos?(?: minimos| basicos| de escolaridade| de ingresso| para (?:o )?(?:cargo|emprego|investidura|provimento|posse|admissao))?"
                    + "|pre-?\\s?requisitos?|escolaridade(?: minima| exigida)?|formacao(?: exigida| minima| academica| escolar)?"
                    + "|habilitacao(?: exigida| minima)?|qualificacao(?: minima| exigida)?|exigencias?)\\s*[:\\-–]");
    static final Pattern ROTULO_PARADA = Padroes.compilar(
            "(?<![a-z])(atribuic(?:oes|ao)(?: sumarias?| do cargo| basicas)?|descricao(?: sumaria)?(?: d[oa]s?)? (?:atribuicoes|cargo|atividades|funcoes|emprego)"
                    + "|sintese das atribuicoes|atividades(?: tipicas| basicas)?(?: d[oa] (?:cargo|emprego|funcao))?"
                    + "|vagas?(?: imediatas)?|n[uo]mero de vagas|quantidade de vagas|total de vagas"
                    + "|remuneracao(?: inicial| mensal| total)?|vencimentos?(?: basico| inicial)?|salario(?: base| basico| inicial| mensal)?|subsidio"
                    + "|carga horaria(?: semanal)?|jornada(?: de trabalho)?|lotacao|local de trabalho|conteudo programatico"
                    + "|conhecimentos especificos|taxa de inscricao|beneficios|especialidade)\\s*[:\\-–]");
    /** Rótulos depois dos quais o texto não é mais requisito, mesmo sem rótulo de requisito no bloco. */
    private static final Pattern PARADA_SEM_ROTULO = Padroes.compilar(
            "(?<![a-z])(atribuic(?:oes|ao)|descricao(?: sumaria)?(?: d[oa]s?)? (?:atribuicoes|cargo|atividades|funcoes|emprego)"
                    + "|sintese das atribuicoes|atividades (?:tipicas|basicas|d[oa] (?:cargo|emprego|funcao))"
                    + "|conteudo programatico|conhecimentos especificos)");

    private static final Pattern PISTA_FORMACAO = Padroes.compilar(
            // "técnico" (substantivo); "técnica" só na forma "técnica de nível médio" — "orientação técnica" não é pista
            "(?<![a-z])(curso|cursos|tecnicos?|tecnica de nivel medio|habilitacao|habilitacoes|formacao|certificado|diploma|profissionalizante"
                    + "|requisitos?|escolaridade)(?![a-z])");
    /**
     * Entre a pista e a formação: experiência/atividade não é formação, e "Graduação em Engenharia
     * Mecânica" não é "Técnico em Mecânica".
     */
    private static final Pattern BLOQUEIO_FORMACAO = Padroes.compilar(
            "(?<![a-z])(experiencia|pratica|conhecimentos?|atuacao|vivencia|atividades?"
                    + "|graduacao|engenharia|bacharelado|licenciatura|tecnologo|tecnologia em|superior)(?![a-z])|\\.\\s");
    private static final int JANELA_PISTA = 120;

    private static final Pattern ESCOLARIDADE = Padroes.compilar(
            "(?<![a-z])((?:ensino|nivel) (?:fundamental|medio|superior)(?: (?:completo|incompleto|tecnico|profissionalizante))?"
                    + "|nivel tecnico|medio tecnico|curso superior(?: completo)?|graduacao|bacharelado|licenciatura|tecnologo)(?![a-z])");
    private static final Pattern TECNICO_GENERICO = Padroes.compilar(
            "(?<![a-z])(curso tecnico|nivel medio tecnico|tecnico de nivel medio|ensino medio (?:tecnico|profissionalizante)"
                    + "|educacao profissional tecnica|habilitacao tecnica|nivel tecnico|medio tecnico|medio profissionalizante)(?![a-z])");
    private static final Pattern QUALIFICACAO_NAO_TECNICA = Padroes.compilar(
            "(?<![a-z])(curso (?:de )?(?:qualificacao|capacitacao|formacao inicial|livre)|curso de eletricista|nr[- ]?10)(?![a-z0-9])");
    /** "Técnico em X" / "Técnico de X" — X é o nome da habilitação, até a próxima vírgula, "ou", "e", "com"... */
    private static final Pattern TECNICO_EM_AREA = Padroes.compilar(
            "(?<![a-z])tecnico (?:em|de|na area de) (?!nivel|qualquer)([a-z][a-z ]{2,50}?)"
                    + "(?=\\s*[;,.:()\\n]|\\s(?:ou|e|com|devidamente|reconhecid|realizad|fornecid|expedid|emitid)\\s|\\s*$)");
    private static final Pattern QUALQUER_AREA = Padroes.compilar(
            "qualquer (?:curso tecnico|area|habilitacao|especialidade|modalidade)");

    private final CatalogoFormacoes catalogo;

    public AnalisadorRequisitos(CatalogoFormacoes catalogo) {
        this.catalogo = catalogo;
    }

    /** Faixas de requisito dos blocos úteis de um cargo. */
    public List<Faixa> faixas(TextoDocumento texto, CargoDetectado cargo) {
        String norm = texto.normalizado();
        List<Faixa> rotuladas = new ArrayList<>();
        List<Faixa> semRotulo = new ArrayList<>();
        for (CargoDetectado.Bloco bloco : cargo.blocosUteis()) {
            int ini = bloco.inicioConteudo();
            int fim = bloco.fim();
            Matcher rotulo = ROTULO_REQUISITO.matcher(norm).region(ini, fim);
            boolean achou = false;
            while (rotulo.find()) {
                achou = true;
                int fimFaixa = fim;
                Matcher parada = ROTULO_PARADA.matcher(norm).region(rotulo.end(), fim);
                if (parada.find()) {
                    fimFaixa = parada.start();
                }
                Matcher outro = ROTULO_REQUISITO.matcher(norm).region(rotulo.end(), fimFaixa);
                if (outro.find()) {
                    fimFaixa = outro.start();
                }
                rotuladas.add(new Faixa(rotulo.end(), fimFaixa, rotulo.group(1)));
            }
            if (!achou) {
                int fimFaixa = fim;
                Matcher parada = PARADA_SEM_ROTULO.matcher(norm).region(ini, fim);
                if (parada.find()) {
                    fimFaixa = parada.start();
                }
                if (fimFaixa > ini) {
                    semRotulo.add(new Faixa(ini, fimFaixa, null));
                }
            }
        }
        return rotuladas.isEmpty() ? semRotulo : rotuladas;
    }

    public AnaliseRequisito analisar(TextoDocumento texto, List<Faixa> faixas) {
        String norm = texto.normalizado();
        Map<String, FormacaoEncontrada> formacoes = new LinkedHashMap<>();
        Evidencia generico = null;
        boolean qualquerArea = false;
        Campo<String> escolaridade = Campo.naoEncontrado();
        Evidencia qualificacao = null;
        Set<String> naoCatalogadas = new LinkedHashSet<>();

        for (Faixa faixa : faixas) {
            List<CatalogoFormacoes.Ocorrencia> ocorrencias = catalogo.encontrar(norm, faixa.inicio(), faixa.fim());
            for (CatalogoFormacoes.Ocorrencia o : ocorrencias) {
                if (!formacoes.containsKey(o.entrada().id()) && temPista(norm, o.inicio(), faixa.rotulada())) {
                    formacoes.put(o.entrada().id(), new FormacaoEncontrada(o.entrada(),
                            texto.original().substring(o.inicio(), o.fim()),
                            texto.evidencia(o.inicio(), o.fim(), MetodoExtracao.REGEX)));
                }
            }
            Matcher area = TECNICO_EM_AREA.matcher(norm).region(faixa.inicio(), faixa.fim());
            while (area.find()) {
                boolean conhecida = ocorrencias.stream().anyMatch(o -> o.inicio() < area.end(1) && area.start(1) < o.fim());
                if (!conhecida) {
                    naoCatalogadas.add(TextoDocumento.compactar(texto.original().substring(area.start(), area.end(1))));
                }
            }
            if (generico == null) {
                Matcher m = TECNICO_GENERICO.matcher(norm).region(faixa.inicio(), faixa.fim());
                if (m.find()) {
                    generico = texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX);
                }
            }
            if (QUALQUER_AREA.matcher(norm).region(faixa.inicio(), faixa.fim()).find()) {
                qualquerArea = true;
            }
            if (!escolaridade.presente()) {
                Matcher m = ESCOLARIDADE.matcher(norm).region(faixa.inicio(), faixa.fim());
                if (m.find()) {
                    escolaridade = Campo.encontrado(texto.original().substring(m.start(), m.end()),
                            texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
                }
            }
            if (qualificacao == null) {
                Matcher m = QUALIFICACAO_NAO_TECNICA.matcher(norm).region(faixa.inicio(), faixa.fim());
                if (m.find()) {
                    qualificacao = texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX);
                }
            }
        }

        boolean rotulado = faixas.stream().anyMatch(Faixa::rotulada);
        boolean localizado = rotulado || !formacoes.isEmpty() || generico != null || escolaridade.presente()
                || !naoCatalogadas.isEmpty();
        Evidencia trecho = null;
        Faixa primeira = faixas.stream().filter(Faixa::rotulada).findFirst().orElse(null);
        if (primeira != null && primeira.fim() > primeira.inicio()) {
            int fimTrecho = Math.min(primeira.fim(), primeira.inicio() + 40);
            trecho = texto.evidencia(primeira.inicio(), fimTrecho, MetodoExtracao.REGEX);
        } else if (!formacoes.isEmpty()) {
            trecho = formacoes.values().iterator().next().evidencia();
        } else if (generico != null) {
            trecho = generico;
        } else if (escolaridade.presente()) {
            trecho = escolaridade.evidencia();
        }
        return new AnaliseRequisito(localizado, rotulado, trecho, List.copyOf(formacoes.values()),
                formacoes.isEmpty() ? generico : null, qualquerArea, escolaridade, qualificacao, List.copyOf(naoCatalogadas));
    }

    /**
     * A formação só conta se vier depois de uma pista ("curso", "técnico", "habilitação"...) sem
     * nada no meio que mude o sentido ("experiência em manutenção" não é formação).
     */
    private static boolean temPista(String norm, int posicao, boolean faixaRotulada) {
        int inicio = Math.max(0, posicao - JANELA_PISTA);
        String janela = norm.substring(inicio, posicao);
        Matcher m = PISTA_FORMACAO.matcher(janela);
        int fimPista = -1;
        while (m.find()) {
            fimPista = m.end();
        }
        if (fimPista < 0) {
            // Faixa rotulada começando direto na formação: "Requisitos: Eletrotécnica"
            return faixaRotulada && janela.strip().endsWith(":");
        }
        return !BLOQUEIO_FORMACAO.matcher(janela.substring(fimPista)).find();
    }
}
