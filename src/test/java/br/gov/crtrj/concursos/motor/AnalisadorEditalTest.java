package br.gov.crtrj.concursos.motor;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.MomentoRegistro;
import br.gov.crtrj.concursos.motor.dominio.SimNao;
import br.gov.crtrj.concursos.motor.dominio.StatusCampo;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalisadorEditalTest {

    private static final OrigemDocumento OFICIAL = new OrigemDocumento("edital.pdf", "https://niteroi.rj.gov.br/edital.pdf", true, "ORGAO_OFICIAL");

    private static ResultadoAnalise prefeitura;
    private static ResultadoAnalise tabela;

    @BeforeAll
    static void analisar() throws IOException {
        AnalisadorEdital analisador = new AnalisadorEdital(CatalogoFormacoes.carregarPadrao());
        prefeitura = analisador.analisarPdf(EditalSintetico.prefeitura(), OFICIAL);
        tabela = analisador.analisarPdf(EditalSintetico.tabela(), OFICIAL);
    }

    private static CargoAnalisado cargo(ResultadoAnalise r, String trechoDoNome) {
        return r.cargos().stream()
                .filter(c -> c.nome().valor().toLowerCase().contains(trechoDoNome.toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("cargo não encontrado: " + trechoDoNome + " em "
                        + r.cargos().stream().map(c -> c.nome().valor()).toList()));
    }

    @Test
    void cargoComFormacaoIndustrialEConselhoGenericoEhCompativel() {
        CargoAnalisado c = cargo(prefeitura, "manutenção");
        assertThat(c.classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(c.formacoes()).extracting(f -> f.entrada().id()).containsExactly("eletrotecnica");
        assertThat(c.especialidade().valor()).isEqualTo("ELETROTÉCNICA");
        assertThat(c.motivo()).contains("Edital nº 01/2026").contains("p. 1").contains("item 2.1").contains("Eletrotécnica");

        assertThat(c.conselho().exigido()).isEqualTo(SimNao.SIM);
        assertThat(c.conselho().tipo()).isEqualTo(TipoConselho.CRT);
        assertThat(c.conselho().literal().valor()).isEqualTo("Conselho Regional dos Técnicos Industriais");
        assertThat(c.conselho().exigeCrtRj()).isEqualTo(SimNao.NAO_INFORMADO);
        assertThat(c.conselho().exigenciaGenerica()).isFalse();
    }

    @Test
    void separaSalarioBaseDeRemuneracaoEExtraiVagas() {
        CargoAnalisado c = cargo(prefeitura, "manutenção");
        assertThat(c.dados().salarioBase().valor()).isEqualByComparingTo("4250.00");
        assertThat(c.dados().remuneracaoTotal().valor()).isEqualByComparingTo("5180.00");
        assertThat(c.dados().cargaHorariaSemanal().valor()).isEqualTo(40);
        assertThat(c.dados().vagasTotal().valor()).isEqualTo(8);
        assertThat(c.dados().vagasAmpla().valor()).isEqualTo(7);
        assertThat(c.dados().vagasPcd().valor()).isEqualTo(1);
        assertThat(c.dados().cadastroReserva().presente()).isTrue();
        assertThat(c.taxaInscricao().valor()).isEqualByComparingTo("75.00");
    }

    @Test
    void conteudoProgramaticoNaoViraRequisito() {
        // "Eletrotécnica: circuitos..." no Anexo I não pode contar como formação de nenhum cargo
        CargoAnalisado quimica = cargo(prefeitura, "química");
        assertThat(quimica.formacoes()).extracting(f -> f.entrada().id()).containsExactly("quimica");
    }

    @Test
    void tituloComTecnicoNaoBastaSemFormacao() {
        CargoAnalisado c = cargo(prefeitura, "administrativo");
        assertThat(c.classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(c.escolaridade().valor()).isEqualTo("Ensino médio completo");
        // o trecho citado não pode carregar linhas do cargo anterior
        assertThat(c.evidenciaClassificacao().trecho()).isEqualTo("REQUISITOS: Ensino médio completo.");
        assertThat(c.motivo()).doesNotContain("4.250,00");
        // cláusula geral "quando exigido para o cargo" não é tratada como exigência
        assertThat(c.conselho().exigenciaGenerica()).isTrue();
        assertThat(c.conselho().exigido()).isEqualTo(SimNao.NAO_INFORMADO);
        assertThat(c.conselho().momento()).isEqualTo(MomentoRegistro.POSSE);
    }

    @Test
    void tecnicoDeOutraAreaNaoEhCompativel() {
        CargoAnalisado c = cargo(prefeitura, "enfermagem");
        assertThat(c.classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(c.conselho().tipo()).isEqualTo(TipoConselho.OUTRO);
        assertThat(c.conselho().exigeCrtRj()).isEqualTo(SimNao.NAO);
    }

    @Test
    void qualquerAreaOuFormacaoNaoValidadaFicamComoPossivel() {
        assertThat(cargo(prefeitura, "operação").classificacao()).isEqualTo(Classificacao.POSSIVELMENTE_COMPATIVEL);
        assertThat(cargo(prefeitura, "operação").motivo()).contains("qualquer área");
        assertThat(cargo(prefeitura, "química").classificacao()).isEqualTo(Classificacao.POSSIVELMENTE_COMPATIVEL);
    }

    @Test
    void cargoSemSinalTecnicoEhDescartado() {
        assertThat(prefeitura.cargos()).noneMatch(c -> c.nome().valor().contains("PROFESSOR"));
        assertThat(prefeitura.cargosDescartados()).isEqualTo(1);
    }

    @Test
    void dadosDoConcurso() {
        var k = prefeitura.concurso();
        assertThat(k.orgao().valor()).isEqualTo("PREFEITURA MUNICIPAL DE NITERÓI");
        assertThat(k.esfera().valor()).isEqualTo("Municipal");
        assertThat(k.uf().valor()).isEqualTo("RJ");
        assertThat(k.municipio().valor()).isEqualTo("NITERÓI");
        assertThat(k.numeroEdital().valor()).isEqualTo("01/2026");
        assertThat(k.banca().valor()).isEqualTo("Fundação Getulio Vargas (FGV)");
        assertThat(k.banca().status()).isEqualTo(StatusCampo.ENCONTRADO);
        assertThat(k.inscricaoInicio().valor()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(k.inscricaoFim().valor()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(k.isencaoFim().valor()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(k.siteInscricao().valor()).isEqualTo("https://conhecimento.fgv.br/concursos/niteroi26");
        assertThat(k.dataProva().valor()).isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(k.etapas()).extracting(e -> e.valor()).contains("Prova objetiva", "Prova prática");
    }

    @Test
    void todaEvidenciaFoiVerificadaNoTexto() {
        for (CargoAnalisado c : prefeitura.cargos()) {
            assertThat(c.nome().evidencia().verificada()).isTrue();
            c.formacoes().forEach(f -> assertThat(f.evidencia().verificada()).isTrue());
            if (c.evidenciaClassificacao() != null) {
                assertThat(c.evidenciaClassificacao().verificada()).isTrue();
            }
        }
    }

    @Test
    void tabelaComCrtRjExplicitoEValorSemRotulo() {
        CargoAnalisado c = cargo(tabela, "eletromecânica");
        assertThat(c.nome().valor()).isEqualTo("Técnico de Operação – Eletromecânica");
        assertThat(c.classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(c.conselho().tipo()).isEqualTo(TipoConselho.CRT_RJ);
        assertThat(c.conselho().exigeCrtRj()).isEqualTo(SimNao.SIM);
        assertThat(c.conselho().registroAtivo()).isEqualTo(SimNao.SIM);
        // valor sem rótulo não vira salário-base
        assertThat(c.dados().salarioBase().presente()).isFalse();
        assertThat(c.dados().valorQuadro().status()).isEqualTo(StatusCampo.AMBIGUO);
        assertThat(c.dados().valorQuadro().valor()).isEqualByComparingTo(new BigDecimal("3900.00"));
    }

    @Test
    void tabelaSegurancaDoTrabalhoComMte() {
        CargoAnalisado c = cargo(tabela, "segurança");
        assertThat(c.classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(c.conselho().tipo()).isEqualTo(TipoConselho.MTE);
        assertThat(c.conselho().exigeCrtRj()).isEqualTo(SimNao.NAO);
    }

    @Test
    void tituloTecnicoEmEdificacoesComRequisitoEnsinoMedioNaoEhCompativel() {
        CargoAnalisado c = cargo(tabela, "edificações");
        assertThat(c.classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(c.formacoes()).isEmpty();
    }

    @Test
    void numeroDoEditalSemNo() throws IOException {
        // capa do edital de Queimados (IAN): "Edital de Abertura 01/2026", sem "nº"
        AnalisadorEdital analisador = new AnalisadorEdital(CatalogoFormacoes.carregarPadrao());
        ResultadoAnalise r = analisador.analisarPdf(
                EditalSintetico.prefeitura(t -> t.replace("EDITAL DE CONCURSO PÚBLICO Nº 01/2026", "Edital de Abertura 01/2026")),
                OFICIAL);
        assertThat(r.concurso().numeroEdital().valor()).isEqualTo("01/2026");
    }

    @Test
    void fonteNaoOficialNuncaGeraCompativel() throws IOException {
        AnalisadorEdital analisador = new AnalisadorEdital(CatalogoFormacoes.carregarPadrao());
        ResultadoAnalise r = analisador.analisarPdf(EditalSintetico.prefeitura(),
                new OrigemDocumento("copia.pdf", "https://www.pciconcursos.com.br/x.pdf", false, "AGREGADOR"));
        assertThat(r.cargos()).noneMatch(c -> c.classificacao() == Classificacao.COMPATIVEL);
        assertThat(cargo(r, "manutenção").classificacao()).isEqualTo(Classificacao.POSSIVELMENTE_COMPATIVEL);
    }
}
