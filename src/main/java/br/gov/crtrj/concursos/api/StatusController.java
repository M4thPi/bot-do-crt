package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.motor.AnalisadorEdital;
import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.catalogo.EntradaFormacao;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    public record Status(String versaoExtrator, long concursos, int formacoesNoCatalogo) {
    }

    private final ConcursoRepositorio concursos;
    private final CatalogoFormacoes catalogo;

    public StatusController(ConcursoRepositorio concursos, CatalogoFormacoes catalogo) {
        this.concursos = concursos;
        this.catalogo = catalogo;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(AnalisadorEdital.VERSAO_EXTRATOR, concursos.contar(), catalogo.entradas().size());
    }

    /** Catálogo de formações usado na validação (industrial = null → ainda não validada). */
    @GetMapping("/formacoes")
    public List<EntradaFormacao> formacoes() {
        return catalogo.entradas();
    }
}
