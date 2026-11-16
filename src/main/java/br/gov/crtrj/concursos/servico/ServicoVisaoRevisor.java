package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.coleta.ServicoColeta;
import br.gov.crtrj.concursos.config.ConcursosProperties;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.Fila;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio.DocumentoLinha;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio.EvidenciaLinha;
import br.gov.crtrj.concursos.persistencia.ExecucaoColetaRepositorio;
import br.gov.crtrj.concursos.persistencia.ExecucaoColetaRepositorio.ExecucaoLinha;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.PistaLinha;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.nulo;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.titulo;
import static br.gov.crtrj.concursos.servico.ApresentacaoCargo.valores;

/**
 * O que quem revisa precisa ver: a fila, cada cargo com os trechos do edital que sustentam os dados, e os concursos
 * que o robô não resolveu sozinho. Nada daqui é público.
 */
@Service
public class ServicoVisaoRevisor {

    // ------------------------------------------------------------------ modelos

    public record SituacaoColeta(boolean habilitada, boolean emAndamento, String intervalo, LocalDateTime ultima,
                                 String resumoUltima, long pistasAguardando, long pistasNaFila) {
    }

    /**
     * @param encerrada prazo de inscrição já passou (fica no fim da fila)
     */
    public record LinhaFila(long id, String cargo, long concursoId, String concurso, String classificacao,
                            String classificacaoRotulo, String classificacaoPublicada, LocalDate inscricaoFim,
                            boolean encerrada, boolean oficial, LocalDateTime revisadoEm) {
    }

    /**
     * @param chave valor do parâmetro {@code aba} para listar esta fila
     */
    public record Aba(String chave, String rotulo, long total) {
    }

    public record FilaRevisao(String aba, List<Aba> abas, List<LinhaFila> itens, SituacaoColeta coleta) {
    }

    /**
     * @param verificada        o trecho foi reencontrado na página indicada (score ≥ limiar)
     * @param mesmoTrechoAcima  mesmo trecho da linha anterior (basta mostrar uma vez)
     */
    public record LinhaEvidencia(String rotulo, String valor, String trecho, int pagina, String local,
                                 boolean verificada, boolean mesmoTrechoAcima) {
    }

    /**
     * Um dado como vai aparecer para o público; o revisor pode ocultá-lo na aprovação.
     *
     * @param chave      nome a enviar em {@code ocultar}
     * @param doConcurso vale para o concurso inteiro (aparece no cabeçalho, junto dos outros cargos)
     */
    public record CampoPrevia(String chave, String rotulo, String valor, boolean oculto, boolean doConcurso) {
    }

    /**
     * @param sugestao        classificação a publicar se o revisor não escolher outra (null: o motor não a considera publicável)
     * @param paginaInicial   página do PDF onde está o requisito, para abrir o documento nela
     * @param naoIdentificados dados que o motor não achou no edital
     * @param alteracoes      mudanças já registradas no concurso ("data — campo: antes → depois")
     * @param publicaveis     classificações que o revisor pode publicar
     * @param previa          os dados como o público vai ler
     */
    public record CargoParaRevisao(CargoLinha cargo, String titulo, ConcursoLinha concurso, String situacao,
                                   DocumentoLinha documento, String classificacaoMotorRotulo, Classificacao sugestao,
                                   String motivo, List<LinhaEvidencia> evidenciasCargo,
                                   List<LinhaEvidencia> evidenciasConcurso, List<String> naoIdentificados,
                                   List<String> alertas, List<String> avisos, int paginaInicial, List<String> alteracoes,
                                   List<Classificacao> publicaveis, List<CampoPrevia> previa) {
    }

    /**
     * @param status    código da pista (SEM_FONTE_OFICIAL, EDITAL_NAO_ENCONTRADO, ERRO_ACESSO, BLOQUEADA)
     * @param situacao  o mesmo, em português
     * @param noticia   página onde o robô viu o concurso
     * @param fonte     onde ele foi visto ("PCI", "Selecon", "DOU"...)
     */
    public record PistaParaRevisor(long id, String orgao, String titulo, String status, String situacao, String motivo,
                                   String noticia, String paginaOficial, String vagas, String cargos, String inscricaoAte,
                                   int tentativas, LocalDateTime atualizadaEm, String fonte) {
    }

    public record PistasAguardando(List<PistaParaRevisor> pistas, SituacaoColeta coleta) {
    }

    // ------------------------------------------------------------------

    /** Ordem e rótulos dos campos do cargo na revisão. */
    private static final Map<String, String> CAMPOS_CARGO = mapa(
            "classificacao", "Trecho que sustenta a classificação",
            "requisito", "Requisito do cargo",
            "formacao", "Formação reconhecida",
            "escolaridade", "Escolaridade",
            "conselho", "Conselho",
            "nome", "Nome do cargo",
            "especialidade", "Especialidade",
            "vagas_total", "Vagas (total)",
            "vagas_imediatas", "Vagas imediatas",
            "vagas_ampla", "Ampla concorrência",
            "vagas_pcd", "Vagas PcD",
            "cadastro_reserva", "Cadastro reserva",
            "salario_base", "Salário-base",
            "remuneracao_total", "Remuneração total",
            "valor_quadro", "Valor no quadro (sem dizer se é base ou total)",
            "carga_horaria", "Carga horária semanal",
            "taxa_inscricao", "Taxa de inscrição",
            "cnh", "CNH",
            "experiencia", "Experiência");

    private static final Map<String, String> CAMPOS_CONCURSO = mapa(
            "orgao", "Órgão",
            "numero_edital", "Nº do edital",
            "banca", "Banca",
            "esfera", "Esfera",
            "uf", "UF",
            "municipio", "Município",
            "inscricao_inicio", "Início das inscrições",
            "inscricao_fim", "Fim das inscrições",
            "isencao", "Isenção",
            "isencao_fim", "Prazo do pedido de isenção",
            "site_inscricao", "Site de inscrição",
            "data_prova", "Data da prova",
            "taxa_inscricao", "Taxa",
            "etapa", "Etapa");

    private final CargoRepositorio cargos;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final EvidenciaRepositorio evidencias;
    private final AlteracaoRepositorio alteracoes;
    private final PistaRepositorio pistas;
    private final ExecucaoColetaRepositorio execucoes;
    private final ServicoConsulta consulta;
    private final ServicoColeta coleta;
    private final ConcursosProperties.Coleta configColeta;
    private final Clock relogio;

    public ServicoVisaoRevisor(CargoRepositorio cargos, ConcursoRepositorio concursos, DocumentoRepositorio documentos,
                               EvidenciaRepositorio evidencias, AlteracaoRepositorio alteracoes, PistaRepositorio pistas,
                               ExecucaoColetaRepositorio execucoes, ServicoConsulta consulta, ServicoColeta coleta,
                               ConcursosProperties props, Clock relogio) {
        this.relogio = relogio;
        this.cargos = cargos;
        this.concursos = concursos;
        this.documentos = documentos;
        this.evidencias = evidencias;
        this.alteracoes = alteracoes;
        this.pistas = pistas;
        this.execucoes = execucoes;
        this.consulta = consulta;
        this.coleta = coleta;
        this.configColeta = props.coleta();
    }

    // ================================================================== fila

    public FilaRevisao fila(Fila aba) {
        LocalDate hoje = LocalDate.now(relogio);
        List<LinhaFila> itens = cargos.fila(aba, hoje).stream()
                .map(i -> new LinhaFila(i.id(), titulo(i.nome(), i.especialidade()), i.concursoId(), i.concurso(),
                        i.classificacao(), Formato.rotulo(i.classificacao()), i.classificacaoPublicada(), i.inscricaoFim(),
                        i.inscricaoFim() != null && i.inscricaoFim().isBefore(hoje), i.oficial(), i.revisadoEm()))
                .toList();
        Map<Fila, Long> contagem = cargos.contarFilas();
        List<Aba> abas = new ArrayList<>();
        for (Fila f : Fila.values()) {
            abas.add(new Aba(chave(f), switch (f) {
                case PENDENTES -> "Pendentes";
                case NAO_COMPATIVEIS -> "Não compatíveis (motor)";
                case APROVADAS -> "Aprovadas";
                case REJEITADAS -> "Rejeitadas";
            }, contagem.getOrDefault(f, 0L)));
        }
        return new FilaRevisao(chave(aba), abas, itens, situacaoColeta());
    }

    /** "NAO_COMPATIVEIS" → "nao-compativeis". */
    public static String chave(Fila fila) {
        return fila.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public SituacaoColeta situacaoColeta() {
        Map<String, Long> porStatus = pistas.contarPorStatus();
        long aguardando = porStatus.getOrDefault("SEM_FONTE_OFICIAL", 0L)
                + porStatus.getOrDefault("EDITAL_NAO_ENCONTRADO", 0L) + porStatus.getOrDefault("ERRO_ACESSO", 0L)
                + porStatus.getOrDefault("BLOQUEADA", 0L);
        ExecucaoLinha ultima = execucoes.ultimas(1).stream().findFirst().orElse(null);
        String resumo = null;
        if (ultima != null && ultima.terminadaEm() != null) {
            resumo = ultima.pistasLidas() + " concursos lidos, " + ultima.pistasNovas() + " novos, "
                    + ultima.editaisAnalisados() + (ultima.editaisAnalisados() == 1 ? " edital analisado, " : " editais analisados, ")
                    + ultima.erros() + (ultima.erros() == 1 ? " erro" : " erros");
        }
        return new SituacaoColeta(configColeta.habilitada(), coleta.emAndamento(), intervalo(configColeta.intervalo()),
                ultima == null ? null : ultima.iniciadaEm(), resumo, aguardando, porStatus.getOrDefault("NOVA", 0L));
    }

    private static String intervalo(Duration d) {
        if (d.toMinutes() % 60 == 0) {
            long horas = d.toHours();
            return horas == 1 ? "1 hora" : horas + " horas";
        }
        return d.toMinutes() + " minutos";
    }

    // ================================================================== um cargo

    public CargoParaRevisao cargo(long cargoId) {
        CargoLinha c = cargos.porId(cargoId).orElseThrow(() -> new NoSuchElementException("Cargo " + cargoId + " não encontrado"));
        ConcursoLinha k = concursos.porId(c.concursoId()).orElseThrow();
        DocumentoLinha d = documentos.porId(c.documentoId()).orElseThrow();
        ServicoConsulta.CargoDetalhe detalhe = consulta.detalhe(cargoId);

        List<EvidenciaLinha> doCargo = evidencias.listarPorCargo(cargoId);
        List<EvidenciaLinha> doConcurso = evidencias.listarDoConcurso(d.id());
        List<LinhaEvidencia> linhasCargo = linhas(doCargo, CAMPOS_CARGO);
        List<LinhaEvidencia> linhasConcurso = linhas(doConcurso, CAMPOS_CONCURSO);

        List<String> faltando = new ArrayList<>();
        Set<String> camposCargo = new TreeSet<>(doCargo.stream().map(EvidenciaLinha::campo).toList());
        Set<String> camposConcurso = new TreeSet<>(doConcurso.stream().map(EvidenciaLinha::campo).toList());
        if (!camposCargo.contains("vagas_total") && !camposCargo.contains("vagas_imediatas")
                && !camposCargo.contains("cadastro_reserva")) {
            faltando.add("Vagas");
        }
        adicionarSeFalta(faltando, camposCargo, "salario_base", "Salário-base");
        adicionarSeFalta(faltando, camposCargo, "remuneracao_total", "Remuneração total");
        adicionarSeFalta(faltando, camposCargo, "carga_horaria", "Carga horária");
        adicionarSeFalta(faltando, camposCargo, "conselho", "Conselho");
        adicionarSeFalta(faltando, camposConcurso, "inscricao_fim", "Período de inscrição");
        adicionarSeFalta(faltando, camposConcurso, "data_prova", "Data da prova");
        adicionarSeFalta(faltando, camposConcurso, "banca", "Banca");

        // página do requisito (ou do trecho da classificação), para abrir o PDF nela
        int pagina = doCargo.stream().filter(e -> e.campo().equals("requisito")).findFirst()
                .or(() -> doCargo.stream().filter(e -> e.campo().equals("classificacao")).findFirst())
                .map(EvidenciaLinha::pagina).orElse(1);

        Classificacao doMotor = Classificacao.valueOf(c.classificacao());
        Classificacao sugestao = c.classificacaoPublicada() != null ? Classificacao.valueOf(c.classificacaoPublicada())
                : ServicoRevisao.PUBLICAVEIS.contains(doMotor) ? doMotor : null;

        List<String> mudancas = alteracoes.listarPorConcurso(k.id()).stream()
                .map(a -> Formato.dataHora(a.detectadaEm()) + " — " + a.campo() + ": "
                        + (a.antes() == null ? "" : a.antes() + " → ") + nulo(a.depois()))
                .toList();

        return new CargoParaRevisao(c, titulo(c), k, consulta.status(k).rotulo(), d, doMotor.rotulo(), sugestao, c.motivo(),
                linhasCargo, linhasConcurso, faltando, detalhe.analise().alertas(), detalhe.avisos(), pagina, mudancas,
                List.of(Classificacao.COMPATIVEL, Classificacao.POSSIVELMENTE_COMPATIVEL), previa(c, k));
    }

    private static List<CampoPrevia> previa(CargoLinha c, ConcursoLinha k) {
        Set<CampoPublico> ocultos = CampoPublico.ler(c.camposOcultos());
        Map<CampoPublico, String> valores = valores(c, k);
        return Arrays.stream(CampoPublico.values())
                .map(f -> new CampoPrevia(f.name(), f.rotulo(), valores.get(f), ocultos.contains(f), f.doConcurso()))
                .toList();
    }

    private static void adicionarSeFalta(List<String> faltando, Set<String> presentes, String campo, String rotulo) {
        if (!presentes.contains(campo)) {
            faltando.add(rotulo);
        }
    }

    private static List<LinhaEvidencia> linhas(List<EvidenciaLinha> ev, Map<String, String> campos) {
        List<String> ordem = new ArrayList<>(campos.keySet());
        List<LinhaEvidencia> linhas = new ArrayList<>();
        String anterior = null;
        for (EvidenciaLinha e : ev.stream()
                .filter(x -> campos.containsKey(x.campo()))
                .sorted(Comparator.comparingInt((EvidenciaLinha x) -> ordem.indexOf(x.campo())).thenComparing(EvidenciaLinha::id))
                .toList()) {
            linhas.add(new LinhaEvidencia(campos.get(e.campo()), valor(e), e.trecho(), e.pagina(),
                    Formato.local(e.pagina(), e.secao(), e.item()), e.score() >= Evidencia.LIMIAR_VERIFICACAO,
                    e.trecho().equals(anterior)));
            anterior = e.trecho();
        }
        return linhas;
    }

    /** Valor gravado na evidência, formatado para leitura. */
    private static String valor(EvidenciaLinha e) {
        String v = e.valor();
        String formatado;
        if (v == null || v.isBlank() || v.equals("null")) {
            formatado = "—";
        } else {
            try {
                formatado = switch (e.campo()) {
                    case "salario_base", "remuneracao_total", "valor_quadro", "taxa_inscricao" ->
                            Formato.dinheiro(new BigDecimal(v));
                    case "inscricao_inicio", "inscricao_fim", "isencao_fim", "data_prova" -> Formato.data(LocalDate.parse(v));
                    case "cadastro_reserva" -> "true".equals(v) ? "Sim" : v;
                    case "classificacao" -> Formato.rotulo(v);
                    case "carga_horaria" -> v + " h/semana";
                    default -> v;
                };
            } catch (RuntimeException formatoInesperado) {
                formatado = v;
            }
        }
        return "AMBIGUO".equals(e.status()) ? formatado + " (a confirmar)" : formatado;
    }

    // ================================================================== pistas

    public PistasAguardando pistasAguardando() {
        return new PistasAguardando(pistas.aguardandoRevisor().stream().map(ServicoVisaoRevisor::pistaParaRevisor).toList(),
                situacaoColeta());
    }

    private static PistaParaRevisor pistaParaRevisor(PistaLinha p) {
        String situacao = switch (p.status()) {
            case "SEM_FONTE_OFICIAL" -> "A notícia não cita site oficial";
            case "EDITAL_NAO_ENCONTRADO" -> "Edital não localizado no site";
            case "ERRO_ACESSO" -> "Site inacessível para o robô";
            case "BLOQUEADA" -> "Site não permite robôs";
            default -> p.status();
        };
        return new PistaParaRevisor(p.id(), Formato.ouNi(p.orgao()), p.titulo(), p.status(), situacao, p.motivo(), p.url(),
                p.urlOficial(), p.vagasTexto(), p.cargosTexto(), p.inscricaoAte(), p.tentativas(), p.atualizadaEm(),
                p.fonte());
    }

    private static Map<String, String> mapa(String... pares) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put(pares[i], pares[i + 1]);
        }
        return m;
    }
}
