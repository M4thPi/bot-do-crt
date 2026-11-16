package br.gov.crtrj.concursos.coleta;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Roda a coleta periodicamente enquanto a aplicação estiver no ar. O intervalo conta a partir do
 * fim da rodada anterior, então rodadas nunca se sobrepõem.
 */
@Component
@ConditionalOnProperty(prefix = "concursos.coleta", name = "habilitada", havingValue = "true")
public class ColetaAgendada {

    private final ServicoColeta coleta;

    public ColetaAgendada(ServicoColeta coleta) {
        this.coleta = coleta;
    }

    @Scheduled(initialDelayString = "${concursos.coleta.atraso-inicial}", fixedDelayString = "${concursos.coleta.intervalo}")
    public void executar() {
        coleta.executar();
    }
}
