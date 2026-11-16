package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.coleta.ServicoColeta;
import br.gov.crtrj.concursos.persistencia.ExecucaoColetaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.StatusPista;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@Validated
public class ColetaController {

    public record SituacaoColeta(boolean emAndamento, List<ExecucaoColetaRepositorio.ExecucaoLinha> ultimas) {
    }

    /** Link do edital informado manualmente para uma pista que o robô não conseguiu resolver. */
    public record EditalManual(@NotBlank String url, Boolean fonteOficial) {
    }

    private final ServicoColeta coleta;
    private final ExecucaoColetaRepositorio execucoes;
    private final PistaRepositorio pistas;

    public ColetaController(ServicoColeta coleta, ExecucaoColetaRepositorio execucoes, PistaRepositorio pistas) {
        this.coleta = coleta;
        this.execucoes = execucoes;
        this.pistas = pistas;
    }

    @Operation(summary = "Dispara uma rodada de coleta agora (roda em segundo plano)")
    @PostMapping("/api/v1/admin/coletas")
    public ResponseEntity<Map<String, String>> disparar() {
        if (!coleta.dispararEmSegundoPlano()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("mensagem", "Já existe uma coleta em andamento."));
        }
        return ResponseEntity.accepted().body(Map.of("mensagem",
                "Coleta iniciada. Acompanhe em GET /api/v1/admin/coletas e GET /api/v1/pistas."));
    }

    @Operation(summary = "Execuções recentes do robô")
    @GetMapping("/api/v1/admin/coletas")
    public SituacaoColeta situacao() {
        return new SituacaoColeta(coleta.emAndamento(), execucoes.ultimas(20));
    }

    @Operation(summary = "Pistas encontradas pelo robô",
            description = "status: NOVA, DESCARTADA, SEM_FONTE_OFICIAL, EDITAL_NAO_ENCONTRADO, ERRO_ACESSO, BLOQUEADA, ANALISADA")
    @GetMapping("/api/v1/pistas")
    public List<PistaRepositorio.PistaLinha> listar(@RequestParam(required = false) StatusPista status,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return pistas.listar(status == null ? null : status.name(), page, size);
    }

    @Operation(summary = "Informa o link do edital oficial de uma pista que o robô não resolveu sozinho")
    @PostMapping(path = "/api/v1/pistas/{id}/edital", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ServicoIngestao.Resumo informarEdital(@PathVariable long id, @RequestBody @Validated EditalManual pedido)
            throws IOException {
        return coleta.informarEdital(id, pedido.url(), null, null, Boolean.TRUE.equals(pedido.fonteOficial()));
    }

    @Operation(summary = "Envia o PDF do edital de uma pista que o robô não resolveu sozinho",
            description = "Para sites com certificado inválido ou que proíbem robôs (pista BLOQUEADA): baixe o PDF pelo"
                    + " navegador e envie aqui. urlOrigem é de onde ele veio.")
    @PostMapping(path = "/api/v1/pistas/{id}/edital", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ServicoIngestao.Resumo enviarEdital(@PathVariable long id, @RequestPart("arquivo") MultipartFile arquivo,
                                               @RequestParam(required = false) String urlOrigem,
                                               @RequestParam(required = false) Boolean fonteOficial) throws IOException {
        return coleta.informarEdital(id, urlOrigem == null || urlOrigem.isBlank() ? null : urlOrigem.strip(),
                arquivo.getBytes(), arquivo.getOriginalFilename(), Boolean.TRUE.equals(fonteOficial));
    }

    @Operation(summary = "Descarta a pista: o robô não tenta mais")
    @PostMapping("/api/v1/pistas/{id}/descartar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void descartar(@PathVariable long id) {
        coleta.descartar(id);
    }
}
