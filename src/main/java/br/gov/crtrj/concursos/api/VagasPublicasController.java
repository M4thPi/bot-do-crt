package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.servico.ServicoVagasPublicas;
import br.gov.crtrj.concursos.servico.ServicoVagasPublicas.VagasPublicas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

/**
 * Único trecho da API sem chave: só o que o revisor aprovou. O site pode chamar direto do navegador
 * (origens em {@code concursos.api.origens-permitidas}).
 */
@RestController
@RequestMapping("/api/v1/publico")
@SecurityRequirements
public class VagasPublicasController {

    /** Uma decisão do revisor (inclusive "Desfazer") leva no máximo isto para aparecer ou sumir. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final ServicoVagasPublicas vagas;

    public VagasPublicasController(ServicoVagasPublicas vagas) {
        this.vagas = vagas;
    }

    @Operation(summary = "Vagas aprovadas pelo revisor, agrupadas por concurso",
            description = "Sem chave de API. Concursos com prazo de inscrição mais próximo primeiro. Dado que o revisor"
                    + " ocultou vem como \"Confira no edital.\"; dado que o edital não traz, como \"Não identificado no"
                    + " edital consultado.\".")
    @GetMapping("/vagas")
    public ResponseEntity<VagasPublicas> vagas(
            @Parameter(description = "abertas (padrão) ou todas, que inclui inscrições encerradas")
            @RequestParam(defaultValue = "abertas") String situacao,
            @Parameter(description = "Nome exato de uma formação, como listado em \"formacoes\" da resposta")
            @RequestParam(required = false) String formacao,
            @Parameter(description = "Texto livre: cargo, especialidade, concurso, órgão ou município")
            @RequestParam(required = false) String busca) {
        boolean todas = switch (situacao.strip().toLowerCase(Locale.ROOT)) {
            case "abertas" -> false;
            case "todas" -> true;
            default -> throw new IllegalArgumentException("situacao deve ser \"abertas\" ou \"todas\".");
        };
        return ResponseEntity.ok().cacheControl(CACHE).body(vagas.vagas(todas, formacao, busca));
    }
}
