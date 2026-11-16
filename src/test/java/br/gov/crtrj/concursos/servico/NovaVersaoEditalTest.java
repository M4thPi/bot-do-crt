package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Nova versão do edital (mesmo órgão e nº): a decisão do revisor vale para o que não mudou. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-versao;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-versao",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=chave-de-teste-com-mais-de-24-caracteres"})
class NovaVersaoEditalTest {

    private static final String URL = "https://www.niteroi.rj.gov.br/editais/01-2026.pdf";

    @Autowired
    ServicoIngestao ingestao;

    @Autowired
    ServicoRevisao revisao;

    @Autowired
    CargoRepositorio cargos;

    @Autowired
    AlteracaoRepositorio alteracoes;

    private CargoLinha cargo(long concursoId, String trecho) {
        return cargos.listarPorConcurso(concursoId).stream().filter(c -> c.nome().contains(trecho)).findFirst().orElseThrow();
    }

    @Test
    void decisaoMantidaSoOndeNadaMudou() throws Exception {
        ServicoIngestao.Resumo v1 = ingestao.ingerirArquivo(EditalSintetico.prefeitura(), "v1.pdf", URL, TipoDocumento.EDITAL, false);
        long k = v1.concursoId();
        revisao.aprovar(cargo(k, "MANUTEN").id(), null, null, Set.of(CampoPublico.TAXA), "conferido");
        revisao.rejeitar(cargo(k, "QUÍMICA").id(), "não é industrial");

        // versão 2: só as vagas de Química mudam
        ServicoIngestao.Resumo v2 = ingestao.ingerirArquivo(
                EditalSintetico.prefeitura(l -> l.equals("VAGAS: 2") ? "VAGAS: 5" : l), "v2.pdf", URL,
                TipoDocumento.EDITAL, false);

        assertThat(v2.concursoId()).isEqualTo(k);
        CargoLinha manutencao = cargo(k, "MANUTEN");
        assertThat(manutencao.documentoId()).isEqualTo(v2.documentoId());
        assertThat(manutencao.revisaoStatus()).isEqualTo("APROVADA");
        assertThat(manutencao.camposOcultos()).isEqualTo("TAXA");
        assertThat(manutencao.revisaoObs()).isEqualTo("conferido");

        CargoLinha quimica = cargo(k, "QUÍMICA");
        assertThat(quimica.vagasTotal()).isEqualTo(5);
        assertThat(quimica.revisaoStatus()).isNotIn("APROVADA", "REJEITADA");
        assertThat(alteracoes.listarPorConcurso(k)).anyMatch(a -> a.campo().equals("Cargo de volta à revisão")
                && a.depois().contains("QUÍMICA"));
    }

    @Test
    void consolidadoSoViraNovaVersaoSeForOMesmoConcurso() throws Exception {
        long k = ingestao.ingerirArquivo(EditalSintetico.tabela(), "tabela.pdf", "https://www.cedae.com.br/edital.pdf",
                TipoDocumento.EDITAL, false).concursoId();

        // edital de outro órgão/nº: não substitui nada
        assertThat(ingestao.ingerirNovaVersao(k, EditalSintetico.prefeitura(l -> l.replace("Niterói", "Maricá")
                .replace("NITERÓI", "MARICÁ")), "outro.pdf", "https://www.cedae.com.br/outro.pdf",
                ValidadorFontes.TipoFonte.ORGAO_OFICIAL)).isEmpty();

        // mesmo concurso republicado: Edificações passa a exigir curso técnico
        assertThat(cargo(k, "Edificações").classificacao()).isEqualTo("NAO_COMPATIVEL");
        byte[] consolidado = EditalSintetico.tabela(l -> l.replace("Edificações Ensino médio completo",
                "Edificações Curso Técnico em Edificações"));
        var versao = ingestao.ingerirNovaVersao(k, consolidado, "https://www.cedae.com.br/consolidado.pdf",
                "Edital consolidado", ValidadorFontes.TipoFonte.ORGAO_OFICIAL);
        assertThat(versao).isPresent();
        assertThat(versao.get().concursoId()).isEqualTo(k);
        assertThat(cargo(k, "Edificações").classificacao()).isEqualTo("COMPATIVEL");
    }
}
