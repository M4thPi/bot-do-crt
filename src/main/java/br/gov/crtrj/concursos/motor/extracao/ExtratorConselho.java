package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.dominio.MomentoRegistro;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.gov.crtrj.concursos.motor.texto.Padroes.compilar;

/**
 * Registro em conselho: captura o texto literal, normaliza para {@link TipoConselho} e nunca
 * deduz — "conselho competente" continua sendo "conselho competente".
 */
public final class ExtratorConselho {

    private record Padrao(TipoConselho tipo, Pattern regex) {
    }

    private record Mencao(TipoConselho tipo, int inicio, int fim) {
    }

    /** Ordem = prioridade quando a mesma frase cita mais de um conselho. */
    private static final List<Padrao> PADROES = List.of(
            new Padrao(TipoConselho.CRT_RJ, compilar(
                    "(?<![a-z])crt\\s*[-/–]?\\s*rj(?![a-z])|conselho regional dos tecnicos industriais do (?:estado do )?rio de janeiro")),
            new Padrao(TipoConselho.CFT, compilar("(?<![a-z])cft(?![a-z])|conselho federal dos tecnicos industriais")),
            new Padrao(TipoConselho.CRT, compilar(
                    "(?<![a-z])crts?(?![a-z])(?!\\s*[-/–]?\\s*rj(?![a-z]))"
                            + "|conselhos? regiona(?:l|is) dos tecnicos industriais(?! do (?:estado do )?rio de janeiro)")),
            new Padrao(TipoConselho.CREA, compilar(
                    "(?<![a-z])(?:crea|confea)(?![a-z])|conselho (?:regional|federal) de engenharia")),
            new Padrao(TipoConselho.MTE, compilar(
                    "ministerio do trabalho|(?<![a-z])(?:mte|srte)(?![a-z])|superintendencia regional do trabalho")),
            new Padrao(TipoConselho.OUTRO, compilar(
                    "(?<![a-z])(?:crq|cfq|coren|cofen|crtr|conter|crf|cff|crc|cfc|cra|cfa|cfta|crta|crbio|crn|cro|crm|oab|crefito|cress|crp|cau)(?![a-z])"
                            + "|conselho (?:regional|federal) (?:de|dos) (?:quimica|enfermagem|farmacia|contabilidade|administracao"
                            + "|tecnicos em radiologia|biologia|nutricionistas|odontologia|tecnicos agricolas|arquitetura)")),
            new Padrao(TipoConselho.GENERICO, compilar(
                    "(?:respectivos?|competentes?|correspondentes?|pertinentes?) (?:orgaos?|conselhos?|entidades?)"
                            + "(?: regionais?)?(?: de classe| profissional| de fiscalizacao(?: profissional| do exercicio profissional)?)?"
                            + "(?: competentes?| respectivos?)?"
                            + "|(?:orgaos?|conselhos?|entidades?) (?:de classe|profissional|de fiscalizacao(?: do exercicio)?(?: profissional)?)"
                            + "(?: competentes?| respectivos?| correspondentes?)?"
                            + "|conselho regional (?:competente|respectivo|correspondente|da categoria|de sua categoria)"))
    );

    private static final Pattern PISTA_REGISTRO = compilar("registr|inscri(?:cao|to|ta)|habilitad|filiad|vinculad");
    private static final Pattern NEGACAO = compilar(
            "(?:nao (?:sera |e )?exigid|dispensad|sem necessidade|nao se exige)[^.;]{0,40}(?:registr|inscri)"
                    + "|(?:registr|inscri)[^.;]{0,40}(?:nao (?:sera |e )?exigid|dispensad)");
    private static final Pattern REGISTRO_ATIVO = compilar(
            "registro (?:profissional )?(?:ativo|regular|valido|em dia)|(?:regularmente|devidamente) (?:registrad|inscrit)"
                    + "|situacao regular|(?<![a-z])ativo(?![a-z])|quite|adimplente|em dia com");
    private static final Pattern MOMENTO_POSSE = compilar("ato da posse|na posse|para (?:a )?posse|por ocasiao da posse|investidura|nomeacao");
    private static final Pattern MOMENTO_INSCRICAO = compilar("ato da inscricao|momento da inscricao|ate o (?:ultimo )?dia (?:das|de) inscric");
    private static final Pattern MOMENTO_CONTRATACAO = compilar("contratacao|admissao");
    private static final Pattern CLAUSULA_GERAL = compilar("posse|investidura|requisitos|admissao|contratacao|nomeacao");
    private static final Pattern CONDICIONAL = compilar(
            "quando (?:for )?exigid|quando (?:for o caso|houver|aplicavel)|se for o caso|conforme o caso|para os cargos que");

    /**
     * @param faixas        faixas de requisito do cargo; se nada for citado nelas, procura uma cláusula
     *                      geral de registro no edital (marcada como exigência genérica)
     * @param areasDeCargos blocos de todos os cargos — a cláusula geral nunca vem de dentro deles,
     *                      senão o conselho de um cargo "vazaria" para outro
     */
    public InfoConselho analisar(TextoDocumento texto, List<Faixa> faixas, List<int[]> areasDeCargos) {
        String norm = texto.normalizado();
        for (Faixa faixa : faixas) {
            List<Mencao> mencoes = mencoes(norm, faixa.inicio(), faixa.fim());
            for (Mencao mencao : mencoes) {
                int[] frase = texto.sentenca(mencao.inicio(), mencao.fim());
                String fraseNorm = norm.substring(frase[0], frase[1]);
                boolean especifico = mencao.tipo() != TipoConselho.GENERICO;
                if (PISTA_REGISTRO.matcher(fraseNorm).find() || (especifico && faixa.rotulada())) {
                    String contexto = faixa.rotulo() == null ? fraseNorm : faixa.rotulo() + " " + fraseNorm;
                    return montar(texto, mencoesNaFrase(mencoes, frase), fraseNorm, contexto, false);
                }
            }
        }
        return clausulaGeral(texto, areasDeCargos);
    }

    private InfoConselho clausulaGeral(TextoDocumento texto, List<int[]> areasDeCargos) {
        String norm = texto.normalizado();
        for (Mencao mencao : mencoes(norm, 0, norm.length())) {
            boolean dentroDeCargo = areasDeCargos.stream().anyMatch(a -> mencao.inicio() >= a[0] && mencao.inicio() < a[1]);
            if (dentroDeCargo || texto.emSecaoExcluida(mencao.inicio())) {
                continue;
            }
            int[] frase = texto.sentenca(mencao.inicio(), mencao.fim());
            String fraseNorm = norm.substring(frase[0], frase[1]);
            if (PISTA_REGISTRO.matcher(fraseNorm).find() && CLAUSULA_GERAL.matcher(fraseNorm).find()) {
                List<Mencao> todas = mencoesNaFrase(mencoes(norm, frase[0], frase[1]), frase);
                return montar(texto, todas, fraseNorm, fraseNorm, true);
            }
        }
        return InfoConselho.naoIdentificado();
    }

    private InfoConselho montar(TextoDocumento texto, List<Mencao> mencoes, String fraseNorm, String contexto,
                                boolean generica) {
        mencoes.sort(Comparator.comparingInt((Mencao m) -> m.tipo().ordinal()).thenComparingInt(Mencao::inicio));
        Mencao principal = mencoes.get(0);

        Set<String> literais = new LinkedHashSet<>();
        for (Mencao m : mencoes) {
            literais.add(TextoDocumento.compactar(texto.original().substring(m.inicio(), m.fim())));
        }
        Campo<String> literal = Campo.encontrado(String.join(" / ", literais),
                texto.evidencia(principal.inicio(), principal.fim(), MetodoExtracao.REGEX));

        SimNao exigido;
        if (NEGACAO.matcher(fraseNorm).find()) {
            exigido = SimNao.NAO;
        } else if (generica && CONDICIONAL.matcher(fraseNorm).find()) {
            exigido = SimNao.NAO_INFORMADO;
        } else {
            exigido = SimNao.SIM;
        }

        boolean citaCrtRj = mencoes.stream().anyMatch(m -> m.tipo() == TipoConselho.CRT_RJ);
        boolean soOutrosEspecificos = mencoes.stream().allMatch(m ->
                m.tipo() == TipoConselho.CREA || m.tipo() == TipoConselho.MTE || m.tipo() == TipoConselho.OUTRO);
        SimNao exigeCrtRj = citaCrtRj ? SimNao.SIM : soOutrosEspecificos ? SimNao.NAO : SimNao.NAO_INFORMADO;

        SimNao ativo = REGISTRO_ATIVO.matcher(fraseNorm).find() ? SimNao.SIM : SimNao.NAO_INFORMADO;
        MomentoRegistro momento = MOMENTO_INSCRICAO.matcher(contexto).find() ? MomentoRegistro.INSCRICAO
                : MOMENTO_POSSE.matcher(contexto).find() ? MomentoRegistro.POSSE
                : MOMENTO_CONTRATACAO.matcher(contexto).find() ? MomentoRegistro.CONTRATACAO
                : MomentoRegistro.NAO_INFORMADO;

        return new InfoConselho(exigido, literal, principal.tipo(), exigeCrtRj, ativo, momento, generica);
    }

    private static List<Mencao> mencoes(String norm, int de, int ate) {
        List<Mencao> todas = new ArrayList<>();
        for (Padrao p : PADROES) {
            Matcher m = p.regex().matcher(norm).region(de, ate);
            while (m.find()) {
                todas.add(new Mencao(p.tipo(), m.start(), m.end()));
            }
        }
        // Menção genérica que se sobrepõe a uma específica ("respectivo Conselho Regional dos Técnicos...") é descartada.
        List<Mencao> especificas = todas.stream().filter(m -> m.tipo() != TipoConselho.GENERICO).toList();
        List<Mencao> resultado = new ArrayList<>();
        for (Mencao m : todas) {
            boolean sobreposta = m.tipo() == TipoConselho.GENERICO && especificas.stream()
                    .anyMatch(e -> m.inicio() < e.fim() + 40 && e.inicio() < m.fim() + 40);
            if (!sobreposta) {
                resultado.add(m);
            }
        }
        resultado.sort(Comparator.comparingInt(Mencao::inicio));
        return resultado;
    }

    private static List<Mencao> mencoesNaFrase(List<Mencao> mencoes, int[] frase) {
        List<Mencao> naFrase = new ArrayList<>();
        for (Mencao m : mencoes) {
            if (m.inicio() >= frase[0] && m.fim() <= frase[1]) {
                naFrase.add(m);
            }
        }
        return naFrase;
    }
}
