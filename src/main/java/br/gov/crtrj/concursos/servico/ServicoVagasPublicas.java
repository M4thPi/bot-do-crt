package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.AlteracaoLinha;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio.DocumentoLinha;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio.EvidenciaLinha;
import br.gov.crtrj.concursos.persistencia.ExecucaoColetaRepositorio;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.conselho;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.formacoes;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.local;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.nulo;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.titulo;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.valores;

/**
 * O que o site pode mostrar ao público. Toda a regra de "o que é público" está em
 * {@link CargoRepositorio#publicados()}: só cargo aprovado na revisão. Esta classe não enxerga a fila, as pistas
 * nem a coleta, de propósito.
 */
@Service
public class ServicoVagasPublicas {

    /** Trecho literal do edital que sustenta um dado. */
    public record Trecho(String texto, String local) {
    }

    /**
     * @param classificacao       COMPATIVEL ou POSSIVELMENTE_COMPATIVEL, como foi publicada
     * @param ajustadaNaRevisao   o revisor publicou com classificação diferente da do motor
     * @param classificacaoMotor  a que a análise automática deu
     * @param justificativa       a do motor, ou a do revisor quando ele corrigiu a classificação
     * @param requisito           trecho do edital com o requisito do cargo
     * @param conselho            o que o edital diz do registro profissional, linha a linha
     */
    public record VagaPublica(long id, String cargo, String classificacao, String classificacaoRotulo,
                              boolean ajustadaNaRevisao, String classificacaoMotor, String classificacaoMotorRotulo,
                              String justificativa, List<String> formacoes, String escolaridade, Trecho requisito,
                              String vagas, String salarioBase, String remuneracaoTotal, String cargaHoraria,
                              String taxa, List<String> conselho, Trecho conselhoTrecho, LocalDate conferidoEm) {
    }

    /** Retificação ou comunicado que o revisor liberou para o público. */
    public record DocumentoPublico(String nome, String url, LocalDate data) {
    }

    /**
     * @param situacao        código do {@link StatusConcurso}; null quando o revisor ocultou as datas
     * @param situacaoRotulo  "Inscrições abertas"... ou "Confira as datas no edital"
     * @param alertaPrazo     "Último dia de inscrição", "Inscrições terminam em 3 dias"; null fora da reta final
     * @param editalUrl       endereço do edital no site oficial (não é a cópia guardada aqui)
     */
    public record ConcursoPublico(long id, String nome, String orgao, String local, String banca, String situacao,
                                  String situacaoRotulo, boolean emAberto, String inscricoes, String prova,
                                  String siteInscricao, String editalUrl, String editalNome, boolean editalOficial,
                                  LocalDate inscricaoFim, String alertaPrazo, List<DocumentoPublico> documentosPosteriores,
                                  List<VagaPublica> vagas) {
    }

    /**
     * @param formacoes   formações das vagas listadas, para montar o filtro
     * @param ultimaBusca fim da última rodada do robô, no horário do servidor
     */
    public record VagasPublicas(List<ConcursoPublico> concursos, int totalVagas, List<String> formacoes,
                                LocalDateTime ultimaBusca) {
    }

    private static final Set<StatusConcurso> EM_ABERTO =
            EnumSet.of(StatusConcurso.PREVISTO, StatusConcurso.EDITAL_PUBLICADO, StatusConcurso.INSCRICOES_ABERTAS);

    private final CargoRepositorio cargos;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final EvidenciaRepositorio evidencias;
    private final AlteracaoRepositorio alteracoes;
    private final ExecucaoColetaRepositorio execucoes;
    private final ServicoConsulta consulta;
    private final Clock relogio;

    public ServicoVagasPublicas(CargoRepositorio cargos, ConcursoRepositorio concursos, DocumentoRepositorio documentos,
                                EvidenciaRepositorio evidencias, AlteracaoRepositorio alteracoes,
                                ExecucaoColetaRepositorio execucoes, ServicoConsulta consulta, Clock relogio) {
        this.cargos = cargos;
        this.concursos = concursos;
        this.documentos = documentos;
        this.evidencias = evidencias;
        this.alteracoes = alteracoes;
        this.execucoes = execucoes;
        this.consulta = consulta;
        this.relogio = relogio;
    }

    /**
     * @param todas    true inclui concursos com inscrições encerradas
     * @param formacao nome de formação (como listado em {@link VagasPublicas#formacoes()})
     * @param busca    texto livre: cargo, órgão ou município
     */
    public VagasPublicas vagas(boolean todas, String formacao, String busca) {
        Map<Long, List<CargoLinha>> porConcurso = new LinkedHashMap<>();
        for (CargoLinha c : cargos.publicados()) {
            porConcurso.computeIfAbsent(c.concursoId(), x -> new ArrayList<>()).add(c);
        }
        String termo = busca == null || busca.isBlank() ? null : Normalizador.normalizar(busca.strip());
        Set<String> formacoesDisponiveis = new TreeSet<>();

        List<ConcursoPublico> lista = new ArrayList<>();
        int total = 0;
        for (var entrada : porConcurso.entrySet()) {
            ConcursoLinha k = concursos.porId(entrada.getKey()).orElseThrow();
            // dado do concurso oculto em qualquer aprovação fica oculto no cabeçalho
            Set<CampoPublico> ocultosConcurso = EnumSet.noneOf(CampoPublico.class);
            entrada.getValue().forEach(c -> CampoPublico.ler(c.camposOcultos()).stream()
                    .filter(CampoPublico::doConcurso).forEach(ocultosConcurso::add));
            boolean datasOcultas = ocultosConcurso.contains(CampoPublico.INSCRICOES);
            StatusConcurso status = consulta.status(k);
            List<VagaPublica> vagas = new ArrayList<>();
            for (CargoLinha c : entrada.getValue()) {
                Set<CampoPublico> ocultos = CampoPublico.ler(c.camposOcultos());
                // formação oculta (extração errada) não entra no filtro
                List<String> formacoes = ocultos.contains(CampoPublico.FORMACOES) ? List.of() : formacoes(c);
                formacoesDisponiveis.addAll(formacoes);
                boolean passaFormacao = formacao == null || formacao.isBlank() || formacoes.contains(formacao);
                boolean passaBusca = termo == null || Normalizador.normalizar(String.join(" ", c.nome(),
                        nulo(c.especialidade()), nulo(k.nome()), nulo(k.orgao()), nulo(k.municipio()))).contains(termo);
                if (passaFormacao && passaBusca) {
                    vagas.add(vagaPublica(c, k, ocultos));
                }
            }
            // com as datas ocultas não dá para dizer se está aberto: na dúvida, mostra
            boolean emAberto = datasOcultas || EM_ABERTO.contains(status);
            // suspenso continua na lista padrão: quem ia se inscrever precisa saber
            if (vagas.isEmpty() || (!todas && !emAberto && status != StatusConcurso.SUSPENSO)) {
                continue;
            }
            DocumentoLinha d = documentos.porId(entrada.getValue().getFirst().documentoId()).orElseThrow();
            Map<CampoPublico, String> valores = valores(entrada.getValue().getFirst(), k);
            lista.add(new ConcursoPublico(k.id(), k.nome(), Formato.ouNi(k.orgao()), local(k),
                    publico(valores, ocultosConcurso, CampoPublico.BANCA),
                    datasOcultas ? null : status.name(),
                    datasOcultas ? "Confira as datas no edital" : status.rotulo(), emAberto,
                    publico(valores, ocultosConcurso, CampoPublico.INSCRICOES),
                    publico(valores, ocultosConcurso, CampoPublico.PROVA),
                    ocultosConcurso.contains(CampoPublico.SITE_INSCRICAO) ? null : k.siteInscricao(),
                    d.url(), d.nome(), d.oficial(), datasOcultas ? null : k.inscricaoFim(),
                    datasOcultas ? null : alertaPrazo(status, k.inscricaoFim()),
                    documentosPosteriores(k.id()), vagas));
            total += vagas.size();
        }
        // prazo de inscrição mais próximo primeiro; sem data no fim
        lista.sort(Comparator.comparing(ConcursoPublico::inscricaoFim, Comparator.nullsLast(Comparator.naturalOrder())));
        return new VagasPublicas(lista, total, List.copyOf(formacoesDisponiveis), ultimaBusca());
    }

    private VagaPublica vagaPublica(CargoLinha c, ConcursoLinha k, Set<CampoPublico> ocultos) {
        List<EvidenciaLinha> ev = evidencias.listarPorCargo(c.id());
        Map<CampoPublico, String> valores = valores(c, k);
        boolean ajustada = !c.classificacaoPublicada().equals(c.classificacao());
        boolean conselhoOculto = ocultos.contains(CampoPublico.CONSELHO);
        return new VagaPublica(c.id(), titulo(c), c.classificacaoPublicada(), Formato.rotulo(c.classificacaoPublicada()),
                ajustada, c.classificacao(), Formato.rotulo(c.classificacao()),
                ajustada ? c.justificativaRevisor() : c.motivo(),
                ocultos.contains(CampoPublico.FORMACOES) ? List.of(CampoPublico.OCULTO) : formacoes(c), c.escolaridade(),
                trecho(ev, "requisito"),
                publico(valores, ocultos, CampoPublico.VAGAS),
                publico(valores, ocultos, CampoPublico.SALARIO_BASE),
                publico(valores, ocultos, CampoPublico.REMUNERACAO),
                publico(valores, ocultos, CampoPublico.CARGA_HORARIA),
                publico(valores, ocultos, CampoPublico.TAXA),
                conselhoOculto ? List.of(CampoPublico.OCULTO) : conselho(c),
                conselhoOculto ? null : trecho(ev, "conselho"),
                c.revisadoEm() == null ? null : c.revisadoEm().toLocalDate());
    }

    private static String publico(Map<CampoPublico, String> valores, Set<CampoPublico> ocultos, CampoPublico campo) {
        return ocultos.contains(campo) ? CampoPublico.OCULTO : valores.get(campo);
    }

    private static Trecho trecho(List<EvidenciaLinha> ev, String campo) {
        return ev.stream().filter(e -> e.campo().equals(campo)).findFirst()
                .map(e -> new Trecho(e.trecho(), Formato.local(e.pagina(), e.secao(), e.item())))
                .orElse(null);
    }

    /** Retificações e comunicados que o revisor mandou listar no concurso. */
    private List<DocumentoPublico> documentosPosteriores(long concursoId) {
        List<DocumentoPublico> lista = new ArrayList<>();
        for (AlteracaoLinha a : alteracoes.doTipo(concursoId, AlteracaoRepositorio.Tipo.NOVO_DOCUMENTO,
                AlteracaoRepositorio.Status.APLICADA)) {
            documentos.porId(a.documentoId()).ifPresent(d -> lista.add(new DocumentoPublico(d.nome(), d.url(),
                    a.decididaEm() == null ? null : a.decididaEm().toLocalDate())));
        }
        return lista;
    }

    /** Reta final das inscrições (até 5 dias). */
    private String alertaPrazo(StatusConcurso status, LocalDate fim) {
        if (status != StatusConcurso.INSCRICOES_ABERTAS || fim == null) {
            return null;
        }
        long dias = ChronoUnit.DAYS.between(LocalDate.now(relogio), fim);
        if (dias == 0) {
            return "Último dia de inscrição";
        }
        return dias <= 5 ? "Inscrições terminam em " + dias + (dias == 1 ? " dia" : " dias") : null;
    }

    private LocalDateTime ultimaBusca() {
        return execucoes.ultimas(1).stream().findFirst()
                .map(e -> e.terminadaEm() != null ? e.terminadaEm() : e.iniciadaEm())
                .orElse(null);
    }
}
