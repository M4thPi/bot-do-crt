package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Erros da API em JSON (RFC 9457, {@code application/problem+json}): {@code status} diz o que houve e {@code detail}
 * explica em português. Os erros do próprio Spring (JSON inválido, parâmetro de tipo errado, rota inexistente) saem no
 * mesmo formato por {@code spring.mvc.problemdetails.enabled}.
 */
@RestControllerAdvice(annotations = RestController.class)
public class TratadorErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorErros.class);

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail naoEncontrado(NoSuchElementException e) {
        return problema(HttpStatus.NOT_FOUND, "Não encontrado", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail entradaInvalida(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "Pedido inválido", e.getMessage());
    }

    /** Parâmetro fora dos limites ({@code size=0}, {@code page=-1}). */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail parametroInvalido(ConstraintViolationException e) {
        String detalhe = e.getConstraintViolations().stream()
                .map(v -> ultimoNo(v.getPropertyPath().toString()) + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problema(HttpStatus.BAD_REQUEST, "Pedido inválido", detalhe);
    }

    @ExceptionHandler(BaixadorDocumentos.FalhaDownload.class)
    ProblemDetail falhaDownload(BaixadorDocumentos.FalhaDownload e) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Documento inacessível", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail falhaLeitura(IOException e) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Documento ilegível",
                "Não foi possível ler o documento: " + e.getMessage());
    }

    /** O que não foi previsto: o detalhe vai para o log, não para quem chamou. */
    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception e) {
        log.error("Erro inesperado na API", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Erro interno. Tente de novo; se continuar, avise quem cuida da API.");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(titulo);
        return p;
    }

    /** "buscar.size" → "size". */
    private static String ultimoNo(String caminho) {
        return caminho.substring(caminho.lastIndexOf('.') + 1);
    }
}
