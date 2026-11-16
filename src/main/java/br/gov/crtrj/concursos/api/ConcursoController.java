package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio;
import br.gov.crtrj.concursos.servico.ServicoConsulta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/concursos")
@Validated
public class ConcursoController {

    public record ConcursoResposta(ConcursoLinha concurso, String situacao) {
    }

    public record ConcursoDetalhe(ConcursoLinha concurso, String situacao, List<CargoRepositorio.CargoLinha> cargos,
                                  List<EvidenciaRepositorio.EvidenciaLinha> evidencias) {
    }

    private final ConcursoRepositorio concursos;
    private final CargoRepositorio cargos;
    private final DocumentoRepositorio documentos;
    private final EvidenciaRepositorio evidencias;
    private final AlteracaoRepositorio alteracoes;
    private final ServicoConsulta consulta;

    public ConcursoController(ConcursoRepositorio concursos, CargoRepositorio cargos, DocumentoRepositorio documentos,
                              EvidenciaRepositorio evidencias, AlteracaoRepositorio alteracoes, ServicoConsulta consulta) {
        this.consulta = consulta;
        this.concursos = concursos;
        this.cargos = cargos;
        this.documentos = documentos;
        this.evidencias = evidencias;
        this.alteracoes = alteracoes;
    }

    @GetMapping
    public CargoRepositorio.Pagina<ConcursoResposta> listar(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                             @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        List<ConcursoResposta> itens = concursos.listar(page, size).stream()
                .map(k -> new ConcursoResposta(k, consulta.status(k).rotulo()))
                .toList();
        return new CargoRepositorio.Pagina<>(itens, concursos.contar(), page, size);
    }

    @GetMapping("/{id}")
    public ConcursoDetalhe detalhe(@PathVariable long id) {
        ConcursoLinha k = buscar(id);
        List<DocumentoRepositorio.DocumentoLinha> docs = documentos.listarPorConcurso(id);
        List<EvidenciaRepositorio.EvidenciaLinha> evid = docs.isEmpty() ? List.of()
                : evidencias.listarDoConcurso(docs.get(docs.size() - 1).id());
        return new ConcursoDetalhe(k, consulta.status(k).rotulo(), cargos.listarPorConcurso(id), evid);
    }

    @GetMapping("/{id}/documentos")
    public List<DocumentoRepositorio.DocumentoLinha> documentos(@PathVariable long id) {
        buscar(id);
        return documentos.listarPorConcurso(id);
    }

    @GetMapping("/{id}/alteracoes")
    public List<AlteracaoRepositorio.AlteracaoLinha> alteracoes(@PathVariable long id) {
        buscar(id);
        return alteracoes.listarPorConcurso(id);
    }

    private ConcursoLinha buscar(long id) {
        return concursos.porId(id).orElseThrow(() -> new NoSuchElementException("Concurso " + id + " não encontrado"));
    }
}
