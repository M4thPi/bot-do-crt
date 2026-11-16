package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.documentos.ArmazenamentoDocumentos;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.motor.ResultadoAnalise;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.servico.ServicoConsulta;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/documentos")
public class DocumentoController {

    private final ServicoIngestao ingestao;
    private final ServicoConsulta consulta;
    private final DocumentoRepositorio documentos;
    private final ArmazenamentoDocumentos armazenamento;

    public DocumentoController(ServicoIngestao ingestao, ServicoConsulta consulta, DocumentoRepositorio documentos,
                               ArmazenamentoDocumentos armazenamento) {
        this.ingestao = ingestao;
        this.consulta = consulta;
        this.documentos = documentos;
        this.armazenamento = armazenamento;
    }

    /**
     * @param fonteOficial o operador confirma que a URL é a fonte oficial (útil para prefeituras fora de .gov.br)
     */
    public record NovoDocumento(@NotBlank String url, TipoDocumento tipo, Boolean fonteOficial) {
    }

    @Operation(summary = "Baixa e analisa um edital a partir da URL do PDF")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServicoIngestao.Resumo> porUrl(@RequestBody @Validated NovoDocumento pedido) throws IOException {
        ServicoIngestao.Resumo resumo = ingestao.ingerirUrl(pedido.url(),
                pedido.tipo() == null ? TipoDocumento.EDITAL : pedido.tipo(), Boolean.TRUE.equals(pedido.fonteOficial()));
        return ResponseEntity.status(resumo.jaAnalisado() ? HttpStatus.OK : HttpStatus.CREATED).body(resumo);
    }

    @Operation(summary = "Analisa um edital enviado como arquivo",
            description = "Sem urlOrigem (ou fonteOficial=true), o documento é tratado como de fonte não confirmada e"
                    + " nenhuma vaga fica COMPATÍVEL.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ServicoIngestao.Resumo> porArquivo(@RequestPart("arquivo") MultipartFile arquivo,
                                                             @RequestParam(required = false) String urlOrigem,
                                                             @RequestParam(required = false) TipoDocumento tipo,
                                                             @RequestParam(required = false) Boolean fonteOficial) throws IOException {
        ServicoIngestao.Resumo resumo = ingestao.ingerirArquivo(arquivo.getBytes(), arquivo.getOriginalFilename(), urlOrigem,
                tipo == null ? TipoDocumento.EDITAL : tipo, Boolean.TRUE.equals(fonteOficial));
        return ResponseEntity.status(resumo.jaAnalisado() ? HttpStatus.OK : HttpStatus.CREATED).body(resumo);
    }

    @Operation(summary = "Cópia guardada do PDF (abra com #page=N para ir à página da evidência)")
    @GetMapping("/{id}/arquivo")
    public ResponseEntity<byte[]> arquivo(@PathVariable long id) {
        DocumentoRepositorio.DocumentoLinha d = documentos.porId(id)
                .orElseThrow(() -> new NoSuchElementException("Documento " + id + " não encontrado"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", ContentDisposition.inline().filename(d.sha256() + ".pdf").build().toString())
                .body(armazenamento.ler(d.arquivoPath()));
    }

    @Operation(summary = "Análise completa gravada para o documento (todas as evidências)")
    @GetMapping("/{id}/analise")
    public ResultadoAnalise analise(@PathVariable long id) {
        return consulta.resultado(id);
    }
}
