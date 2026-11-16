package br.gov.crtrj.concursos.relatorio;

import br.gov.crtrj.concursos.motor.CargoAnalisado;
import br.gov.crtrj.concursos.motor.ResultadoAnalise;
import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.StatusCampo;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.extracao.DadosCargo;
import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;
import br.gov.crtrj.concursos.motor.extracao.FormacaoEncontrada;
import br.gov.crtrj.concursos.motor.extracao.InfoConselho;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Relatório no formato pedido: tabela-resumo e depois o detalhe de cada cargo. Todo campo ausente
 * aparece como "Não identificado no edital consultado." — nada é preenchido por suposição.
 */
public final class RenderizadorMarkdown {

    private static final String NI = Campo.NAO_IDENTIFICADO;
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * @param status status do concurso na data de hoje (calculado a partir das datas do edital)
     * @param url    link a exibir como "Edital oficial" (pode ser o link do arquivo guardado)
     */
    public record Item(ResultadoAnalise resultado, CargoAnalisado cargo, StatusConcurso status, String url) {
    }

    public String renderizar(List<Item> itens) {
        StringBuilder sb = new StringBuilder();
        long concursos = itens.stream().map(i -> i.resultado().documento().sha256()).distinct().count();
        long compativeis = itens.stream().filter(i -> i.cargo().classificacao().ordinal() <= 1).count();
        sb.append("**Foram encontrados ").append(concursos).append(concursos == 1 ? " edital" : " editais")
                .append(" e ").append(itens.size()).append(itens.size() == 1 ? " cargo técnico" : " cargos técnicos")
                .append(" (").append(compativeis).append(" compatível(is) ou possivelmente compatível(is)).**\n\n");
        if (itens.isEmpty()) {
            return sb.toString();
        }
        resumo(sb, itens);
        for (Item item : itens) {
            detalhe(sb, item);
        }
        sb.append("\n_— = não identificado no edital consultado. Valores marcados “(a confirmar)” foram encontrados,"
                + " mas o edital não deixa claro o que representam._\n");
        return sb.toString();
    }

    private void resumo(StringBuilder sb, List<Item> itens) {
        sb.append("| Concurso | Órgão | Cargo | Formação | Vagas | Salário | Conselho | Inscrição até | Banca | Situação | Compatibilidade |\n");
        sb.append("|---|---|---|---|---:|---:|---|---|---|---|---|\n");
        for (Item i : itens) {
            DadosConcurso k = i.resultado().concurso();
            CargoAnalisado c = i.cargo();
            sb.append("| ").append(celula(nomeConcurso(i.resultado())))
                    .append(" | ").append(celula(curto(k.orgao())))
                    .append(" | ").append(celula(curto(c.nome())))
                    .append(" | ").append(celula(formacoesCurto(c)))
                    .append(" | ").append(celula(vagasCurto(c.dados())))
                    .append(" | ").append(celula(salarioCurto(c.dados())))
                    .append(" | ").append(celula(conselhoCurto(c.conselho())))
                    .append(" | ").append(celula(k.inscricaoFim().presente() ? DATA.format(k.inscricaoFim().valor()) : "—"))
                    .append(" | ").append(celula(curto(k.banca())))
                    .append(" | ").append(celula(i.status() == null ? "—" : i.status().rotulo()))
                    .append(" | **").append(c.classificacao().rotulo()).append("** |\n");
        }
        sb.append('\n');
    }

    private void detalhe(StringBuilder sb, Item i) {
        ResultadoAnalise r = i.resultado();
        DadosConcurso k = r.concurso();
        CargoAnalisado c = i.cargo();
        DadosCargo d = c.dados();
        InfoConselho cons = c.conselho();

        sb.append("\n---\n\n### Concurso: ").append(nomeConcurso(r)).append("\n\n");
        linha(sb, "Órgão", texto(k.orgao()));
        linha(sb, "Esfera", texto(k.esfera()));
        linha(sb, "Local", local(k));
        linha(sb, "Cargo", texto(c.nome()));
        linha(sb, "Especialidade", texto(c.especialidade()));
        linha(sb, "Formação exigida", c.formacoes().isEmpty() ? NI
                : c.formacoes().stream().map(f -> f.entrada().nome() + " (“" + f.textoLiteral() + "”)").collect(Collectors.joining("; ")));
        linha(sb, "Escolaridade", texto(c.escolaridade()));
        linha(sb, "Experiência", texto(c.dados().experiencia()));
        linha(sb, "CNH", texto(c.dados().cnh()));
        sb.append('\n');
        linha(sb, "Vagas", vagas(d));
        linha(sb, "Salário-base", salarioBase(d));
        linha(sb, "Remuneração inicial", dinheiro(d.remuneracaoTotal()));
        if (d.valorQuadro().status() == StatusCampo.AMBIGUO && !d.salarioBase().presente() && !d.remuneracaoTotal().presente()) {
            linha(sb, "Valor informado no quadro", dinheiro(d.valorQuadro())
                    + " — o edital não especifica se é salário-base ou remuneração total");
        }
        linha(sb, "Carga horária", d.cargaHorariaSemanal().presente() ? d.cargaHorariaSemanal().valor() + " horas semanais" : NI);
        sb.append('\n');
        conselho(sb, cons);
        sb.append('\n');
        linha(sb, "Banca", texto(k.banca()));
        linha(sb, "Inscrições", inscricoes(k));
        linha(sb, "Taxa", dinheiro(c.taxaInscricao()));
        linha(sb, "Isenção", k.isencao().presente()
                ? k.isencao().valor() + (k.isencaoFim().presente() ? " (pedido até " + DATA.format(k.isencaoFim().valor()) + ")" : "")
                : NI);
        linha(sb, "Site de inscrição", texto(k.siteInscricao()));
        linha(sb, "Prova", k.dataProva().presente() ? DATA.format(k.dataProva().valor()) : NI);
        linha(sb, "Etapas", k.etapas().isEmpty() ? NI : k.etapas().stream().map(Campo::valor).collect(Collectors.joining(", ")));
        linha(sb, "Situação do concurso", i.status() == null ? NI : i.status().rotulo());
        sb.append('\n');
        linha(sb, "Compatibilidade", c.classificacao().rotulo());
        linha(sb, "Justificativa", c.motivo());
        linha(sb, "Fonte da comprovação", fonte(r, c.evidenciaClassificacao()));
        if (c.revisaoNecessaria()) {
            linha(sb, "Revisão humana", "pendente");
        }
        for (String alerta : c.alertas()) {
            linha(sb, "Alerta", alerta);
        }
        sb.append('\n');
        String url = i.url() != null ? i.url() : r.documento().url();
        linha(sb, r.documento().fonteOficial() ? "Edital oficial" : "Documento analisado (fonte não confirmada como oficial)",
                url == null ? "enviado manualmente, sem URL de origem" : "<" + url + ">");
    }

    private static void conselho(StringBuilder sb, InfoConselho cons) {
        if (!cons.literal().presente()) {
            linha(sb, "Conselho profissional", "Não identificado no edital.");
            return;
        }
        String exigido = switch (cons.exigido()) {
            case SIM -> "Sim";
            case NAO -> "Não";
            case NAO_INFORMADO -> "Não informado";
        };
        if (cons.exigenciaGenerica()) {
            exigido += " (exigência genérica do edital, não específica do cargo)";
        }
        linha(sb, "Conselho profissional", exigido);
        linha(sb, "Conselho informado pelo edital", "“" + cons.literal().valor() + "” (" + cons.literal().evidencia().localizacao() + ")");
        linha(sb, "Exige especificamente CRT-RJ", cons.exigeCrtRj().rotulo());
        linha(sb, "Registro ativo", cons.registroAtivo() == SimNao.SIM ? "Obrigatório" : "Não mencionado");
        linha(sb, "Momento da exigência", cons.momento().rotulo());
    }

    // ------------------------------------------------------------ formatação

    public static String nomeConcurso(ResultadoAnalise r) {
        DadosConcurso k = r.concurso();
        String orgao = k.orgao().presente() ? k.orgao().valor() : null;
        String edital = k.numeroEdital().presente() ? "Edital nº " + k.numeroEdital().valor() : r.documento().nome();
        return orgao == null ? edital : orgao + " – " + edital;
    }

    private static String local(DadosConcurso k) {
        if (k.municipio().presente() && k.uf().presente()) {
            return k.municipio().valor() + "/" + k.uf().valor();
        }
        if (k.municipio().presente()) {
            return k.municipio().valor();
        }
        return k.uf().presente() ? k.uf().valor() : NI;
    }

    private static String inscricoes(DadosConcurso k) {
        if (k.inscricaoInicio().presente() && k.inscricaoFim().presente()) {
            return DATA.format(k.inscricaoInicio().valor()) + " até " + DATA.format(k.inscricaoFim().valor());
        }
        return k.inscricaoFim().presente() ? "até " + DATA.format(k.inscricaoFim().valor()) : NI;
    }

    private static String vagas(DadosCargo d) {
        StringBuilder sb = new StringBuilder();
        if (d.vagasTotal().presente()) {
            sb.append(d.vagasTotal().valor());
        } else if (d.vagasImediatas().presente()) {
            sb.append(d.vagasImediatas().valor()).append(" imediata(s)");
        }
        if (d.cadastroReserva().presente()) {
            sb.append(sb.isEmpty() ? "Cadastro reserva" : " + cadastro reserva");
        }
        if (sb.isEmpty()) {
            return NI;
        }
        StringBuilder detalhes = new StringBuilder();
        if (d.vagasAmpla().presente()) {
            detalhes.append("ampla concorrência: ").append(d.vagasAmpla().valor());
        }
        if (d.vagasPcd().presente()) {
            detalhes.append(detalhes.isEmpty() ? "" : "; ").append("PcD: ").append(d.vagasPcd().valor());
        }
        return detalhes.isEmpty() ? sb.toString() : sb + " (" + detalhes + ")";
    }

    private static String salarioBase(DadosCargo d) {
        if (d.salarioBase().presente()) {
            return dinheiro(d.salarioBase());
        }
        return d.remuneracaoTotal().presente() ? "Não informado separadamente no edital." : NI;
    }

    private static String vagasCurto(DadosCargo d) {
        String v = vagas(d);
        return NI.equals(v) ? "—" : v.replace("cadastro reserva", "CR").replace("Cadastro reserva", "CR").replaceAll(" \\(.*\\)$", "");
    }

    private static String salarioCurto(DadosCargo d) {
        if (d.salarioBase().presente()) {
            return dinheiro(d.salarioBase());
        }
        if (d.remuneracaoTotal().presente()) {
            return dinheiro(d.remuneracaoTotal()) + " (total)";
        }
        return d.valorQuadro().status() == StatusCampo.AMBIGUO ? dinheiro(d.valorQuadro()) : "—";
    }

    private static String conselhoCurto(InfoConselho c) {
        if (!c.literal().presente()) {
            return "—";
        }
        return c.tipo() == null ? c.literal().valor() : switch (c.tipo()) {
            case CRT_RJ -> "CRT-RJ";
            case GENERICO -> "genérico";
            default -> c.tipo().name();
        } + (c.exigenciaGenerica() ? " (geral)" : "");
    }

    private static String formacoesCurto(CargoAnalisado c) {
        if (c.formacoes().isEmpty()) {
            return c.escolaridade().presente() ? c.escolaridade().valor() : "—";
        }
        return c.formacoes().stream().map(FormacaoEncontrada::entrada).map(e -> e.nome().replace("Técnico em ", ""))
                .collect(Collectors.joining(", "));
    }

    private static String curto(Campo<String> campo) {
        return campo.status() == StatusCampo.NAO_ENCONTRADO ? "—"
                : campo.valor() + (campo.status() == StatusCampo.AMBIGUO ? " (a confirmar)" : "");
    }

    private static String texto(Campo<String> campo) {
        return switch (campo.status()) {
            case ENCONTRADO -> campo.valor();
            case AMBIGUO -> campo.valor() + " (a confirmar)";
            case NAO_ENCONTRADO -> NI;
        };
    }

    public static String dinheiro(Campo<BigDecimal> campo) {
        return switch (campo.status()) {
            case ENCONTRADO -> "R$ " + moeda(campo.valor());
            case AMBIGUO -> "R$ " + moeda(campo.valor()) + " (a confirmar)";
            case NAO_ENCONTRADO -> NI;
        };
    }

    private static String fonte(ResultadoAnalise r, Evidencia ev) {
        return ev == null ? r.documento().nome() + " (trecho não localizado)" : r.documento().nome() + ", " + ev.localizacao();
    }

    /** DecimalFormat não é thread-safe: um por chamada. */
    private static String moeda(BigDecimal valor) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR"))).format(valor);
    }

    private static void linha(StringBuilder sb, String rotulo, String valor) {
        sb.append("**").append(rotulo).append(":** ").append(valor).append("  \n");
    }

    private static String celula(String texto) {
        return texto.replace("|", "\\|").replace("\n", " ");
    }
}
