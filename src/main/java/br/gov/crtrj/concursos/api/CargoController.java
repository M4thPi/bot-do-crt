package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.catalogo.EntradaFormacao;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio;
import br.gov.crtrj.concursos.servico.CampoPublico;
import br.gov.crtrj.concursos.servico.ServicoConsulta;
import br.gov.crtrj.concursos.servico.ServicoRevisao;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/cargos")
@Validated
public class CargoController {

    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final ServicoConsulta consulta;
    private final CargoRepositorio cargos;
    private final EvidenciaRepositorio evidencias;
    private final CatalogoFormacoes catalogo;
    private final ServicoRevisao revisao;
    private final Clock relogio;

    public CargoController(ServicoConsulta consulta, CargoRepositorio cargos, EvidenciaRepositorio evidencias,
                           CatalogoFormacoes catalogo, ServicoRevisao revisao, Clock relogio) {
        this.revisao = revisao;
        this.relogio = relogio;
        this.consulta = consulta;
        this.cargos = cargos;
        this.evidencias = evidencias;
        this.catalogo = catalogo;
    }

    @Operation(summary = "Busca cargos técnicos com os filtros da especificação",
            description = "Por padrão retorna só COMPATIVEL e POSSIVELMENTE_COMPATIVEL. formato=markdown devolve o relatório"
                    + " no layout pedido (tabela-resumo + detalhes).")
    @GetMapping
    public ResponseEntity<?> buscar(
            @RequestParam(required = false) String uf,
            @RequestParam(required = false) String municipio,
            @Parameter(description = "Ex.: eletrotecnica, \"Técnico em Mecânica\"") @RequestParam(required = false) String formacao,
            @RequestParam(required = false) String especialidade,
            @RequestParam(required = false) BigDecimal salarioMin,
            @RequestParam(required = false) BigDecimal salarioMax,
            @Parameter(description = "CRT_RJ, CRT, CFT, CREA, MTE, OUTRO, GENERICO ou NENHUM") @RequestParam(required = false) String conselho,
            @Parameter(description = "Só editais que citam CFT/CRT literalmente") @RequestParam(required = false) Boolean aceitaCrt,
            @Parameter(description = "Com aceitaCrt, inclui \"conselho competente\" (sem presumir que é CRT)")
            @RequestParam(required = false) Boolean incluirConselhoGenerico,
            @RequestParam(required = false) Boolean semConselho,
            @RequestParam(required = false) StatusConcurso status,
            @RequestParam(required = false) Boolean cadastroReserva,
            @RequestParam(required = false) Boolean vagasImediatas,
            @RequestParam(required = false) String banca,
            @RequestParam(required = false) String orgao,
            @RequestParam(required = false) String esfera,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inscricaoAte,
            @Parameter(description = "Lista separada por vírgula, ou TODAS") @RequestParam(required = false) String classificacao,
            @RequestParam(defaultValue = "json") String formato,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

        String formacaoId = null;
        if (formacao != null && !formacao.isBlank()) {
            formacaoId = catalogo.resolver(formacao).map(EntradaFormacao::id)
                    .orElseThrow(() -> new IllegalArgumentException("Formação não reconhecida no catálogo: " + formacao));
        }
        CargoRepositorio.Filtro filtro = new CargoRepositorio.Filtro(vazioParaNull(uf), vazioParaNull(municipio), formacaoId,
                vazioParaNull(especialidade), salarioMin, salarioMax, vazioParaNull(conselho), aceitaCrt,
                incluirConselhoGenerico, semConselho, status, cadastroReserva, vagasImediatas, vazioParaNull(banca),
                vazioParaNull(orgao), vazioParaNull(esfera), inscricaoAte, classificacoes(classificacao), LocalDate.now(relogio));

        if (formato.equalsIgnoreCase("markdown")) {
            return ResponseEntity.ok().contentType(MARKDOWN).body(consulta.buscarMarkdown(filtro, page, size));
        }
        return ResponseEntity.ok(consulta.buscar(filtro, page, size));
    }

    @Operation(summary = "Detalhe do cargo com a análise completa e as evidências de cada campo")
    @GetMapping("/{id}")
    public ServicoConsulta.CargoDetalhe detalhe(@PathVariable long id) {
        return consulta.detalhe(id);
    }

    @Operation(summary = "Evidências campo a campo (documento, página, item, trecho literal)")
    @GetMapping("/{id}/evidencias")
    public List<EvidenciaRepositorio.EvidenciaLinha> evidencias(@PathVariable long id) {
        cargos.porId(id).orElseThrow(() -> new NoSuchElementException("Cargo " + id + " não encontrado"));
        return evidencias.listarPorCargo(id);
    }

    /**
     * @param classificacao  na aprovação: classificação a publicar (vazio = a do motor)
     * @param justificativa  pública; obrigatória quando a classificação difere da do motor
     * @param ocultar        na aprovação: dados que não vão para a página pública (nomes de CampoPublico)
     * @param observacao     anotação interna
     */
    public record DecisaoRevisao(@NotNull Decisao decisao, Classificacao classificacao, String justificativa,
                                 List<String> ocultar, String observacao) {
        public enum Decisao { APROVADA, REJEITADA, PENDENTE }
    }

    @Operation(summary = "Registra a revisão humana (APROVADA publica o cargo na página pública)")
    @PostMapping("/{id}/revisao")
    public ResponseEntity<Void> revisar(@PathVariable long id, @RequestBody @Validated DecisaoRevisao decisao) {
        switch (decisao.decisao()) {
            case APROVADA -> revisao.aprovar(id, decisao.classificacao(), decisao.justificativa(),
                    CampoPublico.deNomes(decisao.ocultar()), decisao.observacao());
            case REJEITADA -> revisao.rejeitar(id, decisao.observacao());
            case PENDENTE -> revisao.reabrir(id);
        }
        return ResponseEntity.noContent().build();
    }

    private static List<Classificacao> classificacoes(String parametro) {
        if (parametro == null || parametro.isBlank()) {
            return List.of(Classificacao.COMPATIVEL, Classificacao.POSSIVELMENTE_COMPATIVEL);
        }
        if (parametro.equalsIgnoreCase("TODAS")) {
            return List.of();
        }
        return Arrays.stream(parametro.split(","))
                .map(s -> {
                    try {
                        return Classificacao.valueOf(s.strip().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Classificação desconhecida: \"" + s.strip() + "\". Use "
                                + Arrays.stream(Classificacao.values()).map(Enum::name).collect(Collectors.joining(", "))
                                + " ou TODAS.");
                    }
                })
                .toList();
    }

    private static String vazioParaNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
