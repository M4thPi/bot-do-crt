package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Revisão humana: nada vai para a página pública sem aprovação.
 * O revisor pode corrigir a classificação do motor, mas só justificando com o que leu no edital.
 */
@Service
public class ServicoRevisao {

    public static final Set<Classificacao> PUBLICAVEIS =
            Set.of(Classificacao.COMPATIVEL, Classificacao.POSSIVELMENTE_COMPATIVEL);

    private final CargoRepositorio cargos;
    private final Clock relogio;

    public ServicoRevisao(CargoRepositorio cargos, Clock relogio) {
        this.cargos = cargos;
        this.relogio = relogio;
    }

    /**
     * @param classificacao  classificação a publicar; null = a do motor
     * @param justificativa  pública; obrigatória quando {@code classificacao} difere da do motor
     * @param ocultos        dados extraídos errados que não devem ir para a página pública
     * @param observacao     anotação interna
     */
    public void aprovar(long cargoId, Classificacao classificacao, String justificativa, Set<CampoPublico> ocultos,
                        String observacao) {
        CargoLinha cargo = buscar(cargoId);
        Classificacao doMotor = Classificacao.valueOf(cargo.classificacao());
        Classificacao publicada = classificacao != null ? classificacao : doMotor;
        if (!PUBLICAVEIS.contains(publicada)) {
            throw new IllegalArgumentException("Só é possível publicar como COMPATÍVEL ou POSSIVELMENTE COMPATÍVEL. O motor"
                    + " classificou como " + doMotor.rotulo() + ": para publicar, escolha a classificação e justifique com o"
                    + " trecho do edital.");
        }
        String justificativaPublica = null;
        if (publicada != doMotor) {
            justificativaPublica = semBrancos(justificativa);
            if (justificativaPublica == null) {
                throw new IllegalArgumentException("Para publicar como " + publicada.rotulo() + " (o motor classificou como "
                        + doMotor.rotulo() + "), escreva a justificativa citando a página e o item do edital.");
            }
        }
        cargos.registrarRevisao(cargoId, "APROVADA", publicada.name(), limite(justificativaPublica, 2000, "A justificativa"),
                CampoPublico.gravar(ocultos == null ? Set.of() : ocultos), limite(semBrancos(observacao), 1000, "A observação"),
                agora());
    }

    public void rejeitar(long cargoId, String observacao) {
        buscar(cargoId);
        cargos.registrarRevisao(cargoId, "REJEITADA", null, null, null, limite(semBrancos(observacao), 1000, "A observação"),
                agora());
    }

    /** Desfaz a decisão: o cargo sai da página pública e volta para a fila. */
    public void reabrir(long cargoId) {
        buscar(cargoId);
        cargos.registrarRevisao(cargoId, "PENDENTE", null, null, null, null, null);
    }

    private CargoLinha buscar(long id) {
        return cargos.porId(id).orElseThrow(() -> new NoSuchElementException("Cargo " + id + " não encontrado"));
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(relogio);
    }

    private static String semBrancos(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String limite(String s, int max, String rotulo) {
        if (s != null && s.length() > max) {
            throw new IllegalArgumentException(rotulo + " passou de " + max + " caracteres.");
        }
        return s;
    }
}
