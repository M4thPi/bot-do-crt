package br.gov.crtrj.concursos.motor;

import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Tudo o que foi extraído de um documento, com evidências.
 *
 * @param cargosDescartados cargos encontrados sem nenhum sinal de nível técnico (ex.: "Professor", "Analista")
 */
public record ResultadoAnalise(DocumentoAnalisado documento, DadosConcurso concurso, List<CargoAnalisado> cargos,
                               int cargosDescartados, List<String> avisos, String versaoExtrator,
                               OffsetDateTime analisadoEm) {

    /**
     * @param nome            como citar o documento ("Edital nº 01/2026" ou nome do arquivo)
     * @param url             URL de onde foi baixado (null em envio manual)
     * @param fonteOficial    domínio reconhecido como oficial ou confirmado por operador
     * @param tipoFonte       ORGAO_OFICIAL, BANCA, AGREGADOR, DESCONHECIDA, CONFIRMADA_MANUALMENTE, ENVIO_MANUAL
     */
    public record DocumentoAnalisado(String nome, String url, String sha256, int paginas, List<Integer> paginasSemTexto,
                                     boolean fonteOficial, String tipoFonte) {
    }
}
