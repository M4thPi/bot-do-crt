package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.motor.dominio.MomentoRegistro;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static br.gov.crtrj.concursos.servico.Formato.NI;

/**
 * Cada dado do cargo e do concurso como o público lê. A vaga publicada e a prévia que o revisor vê antes de
 * aprovar usam estas mesmas funções, para nunca divergirem.
 */
final class ApresentacaoCargo {

    private ApresentacaoCargo() {
    }

    static Map<CampoPublico, String> valores(CargoLinha c, ConcursoLinha k) {
        Map<CampoPublico, String> v = new EnumMap<>(CampoPublico.class);
        List<String> formacoes = formacoes(c);
        v.put(CampoPublico.FORMACOES, !formacoes.isEmpty() ? String.join(", ", formacoes)
                : c.escolaridade() != null ? "Escolaridade: " + c.escolaridade() : NI);
        v.put(CampoPublico.VAGAS, vagas(c));
        v.put(CampoPublico.SALARIO_BASE, salarioBase(c));
        v.put(CampoPublico.REMUNERACAO, c.remuneracaoTotal() == null ? NI : Formato.dinheiro(c.remuneracaoTotal()));
        v.put(CampoPublico.CARGA_HORARIA, c.cargaHoraria() == null ? NI : c.cargaHoraria() + " horas semanais");
        v.put(CampoPublico.TAXA, c.taxaInscricao() == null ? NI : Formato.dinheiro(c.taxaInscricao()));
        v.put(CampoPublico.CONSELHO, String.join(" · ", conselho(c)));
        v.put(CampoPublico.INSCRICOES, inscricoes(k));
        v.put(CampoPublico.PROVA, k.dataProva() == null ? NI : Formato.data(k.dataProva()));
        v.put(CampoPublico.BANCA, Formato.ouNi(k.banca()));
        v.put(CampoPublico.SITE_INSCRICAO, Formato.ouNi(k.siteInscricao()));
        return v;
    }

    static String vagas(CargoLinha c) {
        StringBuilder sb = new StringBuilder();
        if (c.vagasTotal() != null) {
            sb.append(c.vagasTotal());
        } else if (c.vagasImediatas() != null) {
            sb.append(c.vagasImediatas()).append(" imediata(s)");
        }
        if (c.cadastroReserva()) {
            sb.append(sb.isEmpty() ? "Cadastro reserva" : " + cadastro reserva");
        }
        if (sb.isEmpty()) {
            return NI;
        }
        List<String> detalhes = new ArrayList<>();
        if (c.vagasAmpla() != null) {
            detalhes.add("ampla concorrência: " + c.vagasAmpla());
        }
        if (c.vagasPcd() != null) {
            detalhes.add("PcD: " + c.vagasPcd());
        }
        return detalhes.isEmpty() ? sb.toString() : sb + " (" + String.join("; ", detalhes) + ")";
    }

    static String salarioBase(CargoLinha c) {
        if (c.salarioBase() != null) {
            return Formato.dinheiro(c.salarioBase());
        }
        if (c.remuneracaoTotal() != null) {
            return "Não informado separadamente no edital.";
        }
        if (c.valorQuadro() != null) {
            return Formato.dinheiro(c.valorQuadro()) + " (o edital não diz se é salário-base ou remuneração total)";
        }
        return NI;
    }

    /** O que o edital diz sobre conselho — sem presumir CRT-RJ quando o texto não cita. */
    static List<String> conselho(CargoLinha c) {
        if (c.conselhoLiteral() == null) {
            return List.of(NI);
        }
        List<String> linhas = new ArrayList<>();
        String exigido = SimNao.valueOf(c.conselhoExigido()).rotulo();
        linhas.add("Registro exigido: " + exigido + (c.exigenciaGenerica()
                ? " (exigência geral do edital, não específica do cargo)" : ""));
        linhas.add("O edital cita: “" + c.conselhoLiteral() + "”");
        linhas.add("Exige especificamente o CRT-RJ: " + SimNao.valueOf(c.exigeCrtRj()).rotulo());
        if (SimNao.SIM.name().equals(c.registroAtivo())) {
            linhas.add("Registro ativo: obrigatório");
        }
        MomentoRegistro momento = MomentoRegistro.valueOf(c.momentoRegistro());
        if (momento != MomentoRegistro.NAO_INFORMADO) {
            linhas.add("Momento da exigência: " + momento.rotulo());
        }
        return linhas;
    }

    static List<String> formacoes(CargoLinha c) {
        return c.formacoesNomes() == null ? List.of() : Arrays.asList(c.formacoesNomes().split("; "));
    }

    static String inscricoes(ConcursoLinha k) {
        if (k.inscricaoInicio() != null && k.inscricaoFim() != null) {
            return Formato.data(k.inscricaoInicio()) + " a " + Formato.data(k.inscricaoFim());
        }
        return k.inscricaoFim() != null ? "até " + Formato.data(k.inscricaoFim()) : NI;
    }

    static String local(ConcursoLinha k) {
        if (k.municipio() != null && k.uf() != null) {
            return k.municipio() + "/" + k.uf();
        }
        return Formato.ouNi(k.municipio() != null ? k.municipio() : k.uf());
    }

    static String titulo(CargoLinha c) {
        return titulo(c.nome(), c.especialidade());
    }

    /** Não repete a especialidade quando ela já está no nome ("Técnico de Laboratório/Área: Eletrônica"). */
    static String titulo(String nome, String especialidade) {
        if (especialidade == null || Normalizador.normalizar(nome).contains(Normalizador.normalizar(especialidade))) {
            return nome;
        }
        return nome + " – " + especialidade;
    }

    static String nulo(String s) {
        return s == null ? "" : s;
    }
}
