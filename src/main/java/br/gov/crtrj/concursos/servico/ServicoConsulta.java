package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.api.CargoResposta;
import br.gov.crtrj.concursos.motor.CargoAnalisado;
import br.gov.crtrj.concursos.motor.ResultadoAnalise;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.Pagina;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio.DocumentoLinha;
import br.gov.crtrj.concursos.relatorio.RenderizadorMarkdown;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Leitura: busca com filtros, detalhe de cargo e relatório em markdown. */
@Service
public class ServicoConsulta {

    public record CargoDetalhe(CargoResposta cargo, CargoAnalisado analise, ResultadoAnalise.DocumentoAnalisado documento,
                               DadosConcurso concurso, List<String> avisos) {
    }

    private final CargoRepositorio cargos;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final RenderizadorMarkdown renderizador;
    private final ObjectMapper json;
    private final Clock relogio;

    public ServicoConsulta(CargoRepositorio cargos, ConcursoRepositorio concursos, DocumentoRepositorio documentos,
                           RenderizadorMarkdown renderizador, ObjectMapper json, Clock relogio) {
        this.relogio = relogio;
        this.cargos = cargos;
        this.concursos = concursos;
        this.documentos = documentos;
        this.renderizador = renderizador;
        this.json = json;
    }

    public Pagina<CargoResposta> buscar(CargoRepositorio.Filtro filtro, int pagina, int tamanho) {
        Pagina<CargoLinha> linhas = cargos.buscar(filtro, pagina, tamanho);
        Cache cache = new Cache();
        List<CargoResposta> itens = linhas.itens().stream().map(c -> resposta(c, cache)).toList();
        return new Pagina<>(itens, linhas.total(), pagina, tamanho);
    }

    public String buscarMarkdown(CargoRepositorio.Filtro filtro, int pagina, int tamanho) {
        Pagina<CargoLinha> linhas = cargos.buscar(filtro, pagina, tamanho);
        Cache cache = new Cache();
        List<RenderizadorMarkdown.Item> itens = new ArrayList<>();
        for (CargoLinha c : linhas.itens()) {
            ResultadoAnalise r = cache.resultado(c.documentoId());
            ConcursoLinha k = cache.concurso(c.concursoId());
            DocumentoLinha d = cache.documento(c.documentoId());
            itens.add(new RenderizadorMarkdown.Item(r, r.cargos().get(c.indice()), status(k),
                    d.url() != null ? d.url() : linkArquivo(d.id())));
        }
        String cabecalho = linhas.total() > itens.size()
                ? "_Mostrando " + itens.size() + " de " + linhas.total() + " cargos (página " + pagina + ")._\n\n" : "";
        return cabecalho + renderizador.renderizar(itens);
    }

    public CargoDetalhe detalhe(long id) {
        CargoLinha c = cargos.porId(id).orElseThrow(() -> new NoSuchElementException("Cargo " + id + " não encontrado"));
        Cache cache = new Cache();
        ResultadoAnalise r = cache.resultado(c.documentoId());
        return new CargoDetalhe(resposta(c, cache), r.cargos().get(c.indice()), r.documento(), r.concurso(), r.avisos());
    }

    public ResultadoAnalise resultado(long documentoId) {
        return new Cache().resultado(documentoId);
    }

    public StatusConcurso status(ConcursoLinha k) {
        return StatusConcurso.porDatas(StatusConcurso.valueOf(k.status()), k.inscricaoInicio(), k.inscricaoFim(),
                k.dataProva(), LocalDate.now(relogio));
    }

    private CargoResposta resposta(CargoLinha c, Cache cache) {
        ConcursoLinha k = cache.concurso(c.concursoId());
        DocumentoLinha d = cache.documento(c.documentoId());
        String local = k.municipio() != null && k.uf() != null ? k.municipio() + "/" + k.uf()
                : k.municipio() != null ? k.municipio() : k.uf();
        String observacao = c.salarioBase() == null && c.remuneracaoTotal() != null
                ? "Salário-base não informado separadamente no edital." : null;
        return new CargoResposta(c.id(), k.id(), k.nome(), k.orgao(), k.esfera(), local, k.banca(), c.nome(),
                c.especialidade(), c.escolaridade(),
                c.formacoesNomes() == null ? List.of() : Arrays.asList(c.formacoesNomes().split("; ")),
                new CargoResposta.Vagas(c.vagasTotal(), c.vagasImediatas(), c.vagasAmpla(), c.vagasPcd(), c.cadastroReserva()),
                new CargoResposta.Remuneracao(c.salarioBase(), c.remuneracaoTotal(),
                        c.salarioBase() == null && c.remuneracaoTotal() == null ? c.valorQuadro() : null, observacao),
                c.cargaHoraria(),
                new CargoResposta.Conselho(c.conselhoExigido(), c.conselhoTipo(), c.conselhoLiteral(), c.exigeCrtRj(),
                        c.registroAtivo(), c.momentoRegistro(), c.exigenciaGenerica()),
                new CargoResposta.Inscricao(k.inscricaoInicio(), k.inscricaoFim(), c.taxaInscricao(), k.siteInscricao()),
                k.dataProva(), status(k).rotulo(), c.classificacao(), c.motivo(), c.revisaoStatus(),
                new CargoResposta.Documento(d.id(), d.nome(), d.url(), d.oficial(), d.tipoFonte(), linkArquivo(d.id())));
    }

    private static String linkArquivo(long documentoId) {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/v1/documentos/{id}/arquivo").buildAndExpand(documentoId).toUriString();
        } catch (IllegalStateException foraDeRequisicao) {
            return "/api/v1/documentos/" + documentoId + "/arquivo";
        }
    }

    /** Evita reler concurso/documento/JSON repetidos numa mesma resposta. */
    private final class Cache {
        private final Map<Long, ConcursoLinha> concursosPorId = new HashMap<>();
        private final Map<Long, DocumentoLinha> documentosPorId = new HashMap<>();
        private final Map<Long, ResultadoAnalise> resultados = new HashMap<>();

        ConcursoLinha concurso(long id) {
            return concursosPorId.computeIfAbsent(id, x -> concursos.porId(x).orElseThrow());
        }

        DocumentoLinha documento(long id) {
            return documentosPorId.computeIfAbsent(id, x -> documentos.porId(x).orElseThrow());
        }

        ResultadoAnalise resultado(long documentoId) {
            return resultados.computeIfAbsent(documentoId, x -> {
                String conteudo = documentos.resultadoJson(x)
                        .orElseThrow(() -> new NoSuchElementException("Documento " + x + " não encontrado"));
                try {
                    return json.readValue(conteudo, ResultadoAnalise.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Análise gravada ilegível para o documento " + x, e);
                }
            });
        }
    }
}
