package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.coleta.ServicoAcompanhamento;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.servico.ServicoAlteracoes;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Retificações, prorrogações e comunicados achados depois do edital. Nada muda para o público até o revisor aplicar.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AcompanhamentoController {

    private static final Logger log = LoggerFactory.getLogger(AcompanhamentoController.class);

    public record PaginaOficial(@NotBlank String url) {
    }

    /** @param fonteOficial o operador confirma que a URL é a fonte oficial */
    public record DocumentoPorUrl(@NotBlank String url, Boolean fonteOficial) {
    }

    /** @param alteracoesParaRevisar 0 = documento já conhecido: nada novo */
    public record ResultadoDocumento(int alteracoesParaRevisar) {
    }

    private final ServicoAlteracoes alteracoes;
    private final ServicoAcompanhamento acompanhamento;
    private final ConcursoRepositorio concursos;

    public AcompanhamentoController(ServicoAlteracoes alteracoes, ServicoAcompanhamento acompanhamento,
                                    ConcursoRepositorio concursos) {
        this.alteracoes = alteracoes;
        this.acompanhamento = acompanhamento;
        this.concursos = concursos;
    }

    @Operation(summary = "Alterações à espera de decisão, por concurso",
            description = "Cada item traz o trecho e a página do documento. \"acao\" diz o que cabe: APLICAR (muda o concurso"
                    + " ou lista o documento), CARGO (pode reabrir o cargo citado) ou VER (só informativa). A resposta"
                    + " traz também os concursos acompanhados e a página oficial de cada um.")
    @GetMapping("/revisao/alteracoes")
    public ServicoAlteracoes.AlteracoesPendentes pendentes() {
        return alteracoes.pendentes();
    }

    // ------------------------------------------------------------------ decisão sobre uma alteração

    @Operation(summary = "Aplica: novo prazo, nova data de prova, suspensão/cancelamento, ou lista o documento no concurso")
    @PostMapping("/alteracoes/{id}/aplicar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void aplicar(@PathVariable long id) {
        acompanhamento.aplicar(id);
    }

    @Operation(summary = "Marca como vista (alteração só informativa)")
    @PostMapping("/alteracoes/{id}/vista")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vista(@PathVariable long id) {
        acompanhamento.marcarVista(id);
    }

    @Operation(summary = "Descarta: nada muda no concurso")
    @PostMapping("/alteracoes/{id}/descartar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void descartar(@PathVariable long id) {
        acompanhamento.descartar(id);
    }

    @Operation(summary = "O documento cita um cargo publicado: tira o cargo do público e o devolve à fila de revisão")
    @PostMapping("/alteracoes/{id}/reabrir-cargo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reabrirCargo(@PathVariable long id) {
        acompanhamento.reabrirCargoCitado(id);
    }

    // ------------------------------------------------------------------ por concurso

    @Operation(summary = "Define a página oficial do concurso, onde o robô procura retificações",
            description = "Prevalece sobre a que o robô achou. Vale a partir da próxima rodada.")
    @PutMapping("/concursos/{id}/pagina-oficial")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void paginaOficial(@PathVariable long id, @RequestBody @Validated PaginaOficial pedido) {
        acompanhamento.definirPagina(id, pedido.url());
    }

    @Operation(summary = "Manda o robô visitar a página oficial do concurso agora (roda em segundo plano)",
            description = "Os documentos novos aparecem em GET /api/v1/revisao/alteracoes em alguns minutos.")
    @PostMapping("/concursos/{id}/verificacoes")
    public ResponseEntity<Map<String, String>> verificar(@PathVariable long id) {
        concursos.porId(id).orElseThrow(() -> new NoSuchElementException("Concurso " + id + " não encontrado"));
        // a visita baixa PDFs e respeita o intervalo entre acessos ao mesmo site: roda fora da requisição
        Thread.ofVirtual().name("acompanhamento-" + id).start(() -> {
            try {
                acompanhamento.acompanharAgora(id);
            } catch (RuntimeException e) {
                log.warn("Falha ao verificar o concurso {}", id, e);
            }
        });
        return ResponseEntity.accepted().body(Map.of("mensagem",
                "Verificação iniciada. Os documentos novos aparecem em GET /api/v1/revisao/alteracoes em alguns minutos."));
    }

    @Operation(summary = "Informa a retificação ou comunicado de um concurso por link",
            description = "Para o que o robô não achou sozinho. O documento é lido e vira alterações a decidir.")
    @PostMapping(path = "/concursos/{id}/documentos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResultadoDocumento documentoPorUrl(@PathVariable long id, @RequestBody @Validated DocumentoPorUrl pedido)
            throws IOException {
        return new ResultadoDocumento(acompanhamento.informarDocumento(id, pedido.url().strip(), null, null,
                Boolean.TRUE.equals(pedido.fonteOficial())));
    }

    @Operation(summary = "Envia o PDF da retificação ou comunicado de um concurso",
            description = "Para sites que o robô não pode acessar (robots.txt, certificado). urlOrigem é de onde o PDF veio.")
    @PostMapping(path = "/concursos/{id}/documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultadoDocumento documentoPorArquivo(@PathVariable long id, @RequestPart("arquivo") MultipartFile arquivo,
                                                  @RequestParam(required = false) String urlOrigem,
                                                  @RequestParam(required = false) Boolean fonteOficial) throws IOException {
        return new ResultadoDocumento(acompanhamento.informarDocumento(id,
                urlOrigem == null || urlOrigem.isBlank() ? null : urlOrigem.strip(), arquivo.getBytes(),
                arquivo.getOriginalFilename(), Boolean.TRUE.equals(fonteOficial)));
    }
}
