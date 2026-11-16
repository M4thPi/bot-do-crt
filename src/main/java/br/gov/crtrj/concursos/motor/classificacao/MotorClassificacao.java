package br.gov.crtrj.concursos.motor.classificacao;

import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;
import br.gov.crtrj.concursos.motor.extracao.AnaliseRequisito;
import br.gov.crtrj.concursos.motor.extracao.FormacaoEncontrada;
import br.gov.crtrj.concursos.motor.extracao.InfoConselho;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Regras determinísticas de compatibilidade, avaliadas em ordem. A justificativa é sempre montada
 * por template a partir da evidência — nunca texto livre — então só pode afirmar o que o edital diz.
 */
public final class MotorClassificacao {

    /**
     * @param nomeDocumento  como citar o documento ("Edital nº 01/2026")
     * @param fonteOficial   documento obtido de fonte oficial (órgão, banca, diário oficial)
     * @param escaneado      a maior parte das páginas não tem camada de texto
     */
    public record Contexto(String nomeDocumento, boolean fonteOficial, boolean escaneado) {
    }

    public ResultadoClassificacao classificar(AnaliseRequisito req, InfoConselho conselho, Contexto ctx) {
        ResultadoClassificacao r = aplicarRegras(req, conselho, ctx);
        if (r.classificacao() == Classificacao.COMPATIVEL && !ctx.fonteOficial()) {
            return new ResultadoClassificacao(Classificacao.POSSIVELMENTE_COMPATIVEL,
                    r.motivo() + " Porém o documento não foi obtido de fonte confirmada como oficial;"
                            + " confirme no site do órgão ou da banca.",
                    r.evidencia(), true);
        }
        return r;
    }

    private ResultadoClassificacao aplicarRegras(AnaliseRequisito req, InfoConselho conselho, Contexto ctx) {
        // 1. Sem requisito legível não há como confirmar.
        if (!req.localizado()) {
            String motivo = ctx.escaneado()
                    ? "O documento não tem camada de texto na maior parte das páginas (provável digitalização) e o OCR"
                    + " ainda não está disponível; o requisito do cargo não pôde ser lido."
                    : "O requisito de formação do cargo não foi localizado em " + ctx.nomeDocumento() + ".";
            return new ResultadoClassificacao(Classificacao.NAO_FOI_POSSIVEL_CONFIRMAR, motivo, null, true);
        }

        List<FormacaoEncontrada> industriais = filtrar(req.formacoes(), Boolean.TRUE);
        List<FormacaoEncontrada> aValidar = filtrar(req.formacoes(), null);
        List<FormacaoEncontrada> outras = filtrar(req.formacoes(), Boolean.FALSE);

        // 2. Formação técnica industrial exigida/aceita.
        if (!industriais.isEmpty()) {
            Evidencia ev = industriais.get(0).evidencia();
            return new ResultadoClassificacao(Classificacao.COMPATIVEL,
                    "O requisito do cargo (" + citar(ctx, ev) + ") exige " + aspas(ev.trecho()) + ". "
                            + nomes(industriais) + (industriais.size() > 1 ? " constam" : " consta")
                            + " no catálogo como formação técnica industrial.",
                    ev, true);
        }

        // 3. Registro no sistema CFT/CRT exigido no próprio requisito do cargo.
        if (conselho.exigeSistemaCftCrt()) {
            Evidencia ev = conselho.literal().evidencia();
            return new ResultadoClassificacao(Classificacao.COMPATIVEL,
                    "O requisito do cargo (" + citar(ctx, ev) + ") exige registro em " + conselho.literal().valor()
                            + ": " + aspas(ev.trecho()) + ".",
                    ev, true);
        }

        // 4. Indícios, mas sem confirmação.
        if (!aValidar.isEmpty()) {
            Evidencia ev = aValidar.get(0).evidencia();
            return new ResultadoClassificacao(Classificacao.POSSIVELMENTE_COMPATIVEL,
                    "O requisito (" + citar(ctx, ev) + ") cita " + aspas(ev.trecho()) + ". " + nomes(aValidar)
                            + (aValidar.size() > 1 ? " ainda não foram validadas" : " ainda não foi validada")
                            + " no catálogo como formação técnica industrial.",
                    ev, true);
        }
        // Formação desconhecida pode ser industrial: nunca vira NÃO COMPATÍVEL sem revisão.
        if (!req.naoCatalogadas().isEmpty()) {
            Evidencia ev = req.tecnicoGenerico() != null ? req.tecnicoGenerico() : req.trecho();
            String onde = ev == null ? "" : " (" + citar(ctx, ev) + ")";
            return new ResultadoClassificacao(Classificacao.POSSIVELMENTE_COMPATIVEL,
                    "O requisito" + onde + " cita formação que ainda não consta do catálogo: "
                            + String.join("; ", req.naoCatalogadas())
                            + ". Revise e classifique no catálogo se é técnica industrial.",
                    ev, true);
        }
        if (req.tecnicoGenerico() != null) {
            Evidencia ev = req.tecnicoGenerico();
            String como = req.qualquerArea() ? "aceita curso técnico em qualquer área" : "pede curso técnico sem especificar a área";
            return new ResultadoClassificacao(Classificacao.POSSIVELMENTE_COMPATIVEL,
                    "O requisito (" + citar(ctx, ev) + ") " + como + ": " + aspas(ev.trecho()) + ".", ev, true);
        }

        // 5. Formação ou escolaridade identificada, fora da área técnica industrial.
        if (!outras.isEmpty()) {
            Evidencia ev = outras.get(0).evidencia();
            return new ResultadoClassificacao(Classificacao.NAO_COMPATIVEL,
                    "O requisito (" + citar(ctx, ev) + ") exige " + aspas(ev.trecho()) + "; " + nomes(outras)
                            + (outras.size() > 1 ? " não são formações técnicas industriais." : " não é formação técnica industrial."),
                    ev, false);
        }
        if (req.escolaridade().presente()) {
            Evidencia ev = req.escolaridade().evidencia();
            String complemento = req.qualificacao() != null
                    ? " O curso citado (" + aspas(req.qualificacao().trecho()) + ") é de qualificação, não habilitação técnica."
                    : "";
            return new ResultadoClassificacao(Classificacao.NAO_COMPATIVEL,
                    "O requisito (" + citar(ctx, ev) + ") exige " + aspas(ev.trecho()) + ", sem formação técnica." + complemento,
                    ev, false);
        }

        // 6. Requisito localizado, mas sem nada reconhecível.
        Evidencia ev = req.trecho();
        String onde = ev == null ? "" : " (" + citar(ctx, ev) + ": " + aspas(ev.trecho()) + ")";
        return new ResultadoClassificacao(Classificacao.NAO_FOI_POSSIVEL_CONFIRMAR,
                "O requisito do cargo foi localizado" + onde + ", mas não cita formação ou escolaridade reconhecível.",
                ev, true);
    }

    /** Sinais informativos que não mudam a classificação. */
    public List<String> alertas(AnaliseRequisito req, InfoConselho conselho) {
        boolean industrial = !filtrar(req.formacoes(), Boolean.TRUE).isEmpty();
        if (industrial && conselho.exigido() == SimNao.SIM && conselho.tipo() == TipoConselho.CREA) {
            return List.of("O edital exige registro no CREA para cargo de formação técnica industrial. Desde a"
                    + " Lei 13.639/2018 os técnicos industriais são registrados no sistema CFT/CRTs — possível"
                    + " desatualização do edital. O dado registrado continua sendo o texto literal.");
        }
        return List.of();
    }

    private static List<FormacaoEncontrada> filtrar(List<FormacaoEncontrada> formacoes, Boolean industrial) {
        return formacoes.stream().filter(f -> Objects.equals(f.entrada().industrial(), industrial)).toList();
    }

    private static String nomes(List<FormacaoEncontrada> formacoes) {
        return formacoes.stream().map(f -> f.entrada().nome()).distinct().collect(Collectors.joining(", "));
    }

    /** Trecho entre aspas, sem a pontuação final (evita “...”.). */
    private static String aspas(String trecho) {
        return "“" + trecho.replaceAll("[.;,:\\s]+$", "") + "”";
    }

    private static String citar(Contexto ctx, Evidencia ev) {
        return ctx.nomeDocumento() + ", " + ev.localizacao();
    }
}
