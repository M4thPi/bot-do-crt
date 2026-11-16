package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.persistencia.CargoRepositorio.Fila;
import br.gov.crtrj.concursos.servico.ServicoVisaoRevisor;
import br.gov.crtrj.concursos.servico.ServicoVisaoRevisor.CargoParaRevisao;
import br.gov.crtrj.concursos.servico.ServicoVisaoRevisor.FilaRevisao;
import br.gov.crtrj.concursos.servico.ServicoVisaoRevisor.PistasAguardando;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Dados para a tela de revisão do site. A decisão em si é {@code POST /api/v1/cargos/{id}/revisao}.
 */
@RestController
@RequestMapping("/api/v1/revisao")
public class RevisaoController {

    private final ServicoVisaoRevisor visao;

    public RevisaoController(ServicoVisaoRevisor visao) {
        this.visao = visao;
    }

    @Operation(summary = "Fila de revisão",
            description = "Pendentes: inscrições abertas primeiro, pelo prazo mais próximo. A resposta traz a contagem de"
                    + " cada fila e a situação do robô de coleta.")
    @GetMapping("/fila")
    public FilaRevisao fila(@Parameter(description = "pendentes (padrão), nao-compativeis, aprovadas ou rejeitadas")
                            @RequestParam(defaultValue = "pendentes") String aba) {
        return visao.fila(Arrays.stream(Fila.values())
                .filter(f -> ServicoVisaoRevisor.chave(f).equals(aba.strip().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("aba deve ser uma de: " + Arrays.stream(Fila.values())
                        .map(ServicoVisaoRevisor::chave).collect(Collectors.joining(", ")) + ".")));
    }

    @Operation(summary = "Um cargo para revisar",
            description = "Dados extraídos com o trecho do edital de cada um, o que o motor não achou, a prévia de como o"
                    + " público vai ler e a página do PDF onde está o requisito. O PDF: GET /api/v1/documentos/{id}/arquivo.")
    @GetMapping("/cargos/{id}")
    public CargoParaRevisao cargo(@PathVariable long id) {
        return visao.cargo(id);
    }

    @Operation(summary = "Concursos em que o robô não chegou ao edital",
            description = "Para cada um, o motivo e a página onde foi visto. Resolva com POST /api/v1/pistas/{id}/edital"
                    + " ou descarte com POST /api/v1/pistas/{id}/descartar.")
    @GetMapping("/pistas")
    public PistasAguardando pistas() {
        return visao.pistasAguardando();
    }
}
