package br.gov.crtrj.concursos.motor;

import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.motor.dominio.TipoConselho;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base de teste com editais reais (baixados das fontes oficiais). Cada erro encontrado em edital
 * real vira uma asserção aqui.
 */
class EditaisReaisTest {

    private static ResultadoAnalise uff;
    private static ResultadoAnalise uerj;
    private static ResultadoAnalise tangua;
    private static ResultadoAnalise frontin;

    @BeforeAll
    static void analisar() throws IOException {
        AnalisadorEdital analisador = new AnalisadorEdital(CatalogoFormacoes.carregarPadrao());
        uff = analisador.analisarPdf(ler("uff-96-2025-edital.pdf"),
                new OrigemDocumento("UFF-Edital_96_2025-Edital.pdf",
                        "https://portal.coseac.uff.br/wp-content/uploads/2025/06/UFF-Edital_96_2025-Edital.pdf", true, "ORGAO_OFICIAL"));
        uerj = analisador.analisarPdf(ler("uerj-01-2026.pdf"),
                new OrigemDocumento("edital-uerj.pdf", "https://www.cepuerj.uerj.br/", true, "ORGAO_OFICIAL"));
        // achados pelo robô de coleta nos sites oficiais (a URL exata não foi guardada)
        tangua = analisador.analisarPdf(ler("tangua-001-2026.pdf"),
                new OrigemDocumento("tangua-001-2026.pdf", null, true, "CONFIRMADA_MANUALMENTE"));
        frontin = analisador.analisarPdf(ler("frontin-01-2026.pdf"),
                new OrigemDocumento("frontin-01-2026.pdf", null, true, "CONFIRMADA_MANUALMENTE"));
    }

    private static byte[] ler(String nome) throws IOException {
        try (InputStream in = EditaisReaisTest.class.getResourceAsStream("/editais-reais/" + nome)) {
            return in.readAllBytes();
        }
    }

    private static CargoAnalisado cargo(ResultadoAnalise r, String trecho) {
        return r.cargos().stream().filter(c -> c.nome().valor().contains(trecho)).findFirst()
                .orElseThrow(() -> new AssertionError("cargo não encontrado: " + trecho + " em "
                        + r.cargos().stream().map(c -> c.nome().valor()).toList()));
    }

    // ------------------------------------------------------------------ UFF 96/2025

    @Test
    void uffEngenheirosNuncaSaoCompativeis() {
        // Antes: "reparo ou manutenção" das atividades virava formação; "Engenharia Mecânica" virava "Técnico em Mecânica".
        assertThat(uff.cargos()).noneMatch(c -> c.nome().valor().startsWith("Engenheiro"));
    }

    @Test
    void uffTecnicoDeLaboratorioEletrotecnicaEhCompativelComEvidencia() {
        CargoAnalisado c = cargo(uff, "Eletrotécnica");
        assertThat(c.classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(c.evidenciaClassificacao().trecho()).contains("Curso Técnico em Eletrotécnica");
        assertThat(c.evidenciaClassificacao().verificada()).isTrue();
    }

    @Test
    void uffTecnicoDeTiAceitaTecnicoEmEletronica() {
        assertThat(cargo(uff, "Tecnologia da Informação").classificacao()).isEqualTo(Classificacao.COMPATIVEL);
    }

    @Test
    void uffAssistenteEContabilidadeNaoSaoCompativeis() {
        assertThat(cargo(uff, "Assistente em Administração").classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(cargo(uff, "Contabilidade").classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
    }

    @Test
    void uffOrgaoComSiglaNaFrente() {
        assertThat(uff.concurso().orgao().valor()).isEqualTo("UFF – UNIVERSIDADE FEDERAL FLUMINENSE");
        assertThat(uff.concurso().numeroEdital().valor()).isEqualTo("96/2025");
    }

    // ------------------------------------------------------------------ UERJ 01/CEPUERJ/2026

    @Test
    void uerjNenhumCargoCompativel() {
        assertThat(uerj.cargos()).noneMatch(c -> c.classificacao() == Classificacao.COMPATIVEL);
    }

    @Test
    void uerjNomeQuebradoEmDuasLinhasELinhaDoQuadroDeVagas() {
        // "Técnico" / "Audiovisual" numa célula; "Técnico Audiovisual Duque de Caxias² 01 * * *" no quadro de vagas
        assertThat(uerj.cargos()).extracting(c -> c.nome().valor())
                .contains("Técnico Audiovisual", "Técnico em Enfermagem")
                .noneMatch(n -> n.contains("Caxias") || n.startsWith("Técnicas"));
    }

    @Test
    void uerjEnfermagemComConselhoQuebradoEmDuasLinhas() {
        CargoAnalisado c = cargo(uerj, "Enfermagem");
        assertThat(c.classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(c.conselho().tipo()).isEqualTo(TipoConselho.OUTRO);
        assertThat(c.conselho().literal().valor()).isEqualTo("Conselho Regional de Enfermagem");
    }

    @Test
    void uerjAudiovisualSemFormacaoVazadaDeOutroCargo() {
        CargoAnalisado c = cargo(uerj, "Audiovisual");
        assertThat(c.classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
        assertThat(c.formacoes()).extracting(f -> f.entrada().id()).containsExactly("audiovisual");
    }

    @Test
    void uerjNumeroDoEditalComSiglaDaBanca() {
        assertThat(uerj.concurso().numeroEdital().valor()).isEqualTo("01/CEPUERJ/2026");
    }

    // ------------------------------------------------------------------ Tanguá 001/2026 (quadro em tabela)

    @Test
    void tanguaTecnicoDeSegurancaCompativelEAgricolaNao() {
        assertThat(cargo(tangua, "Segurança").classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(cargo(tangua, "Agrícola").classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
    }

    @Test
    void tanguaDataDaProvaVemDoCronogramaENaoDeLeiCitada() {
        // Antes: "…prova objetiva, na forma … da Lei Federal nº 10.741, de 01 de outubro de 2003" virava a data da prova.
        // No cronograma: "22/11/26 Realização das Provas: Objetiva de Múltipla Escolha…"
        assertThat(tangua.concurso().dataProva().valor()).isEqualTo(LocalDate.of(2026, 11, 22));
    }

    // ------------------------------------------------------------------ Engenheiro Paulo de Frontin 01/2026

    @Test
    void frontinFiscalDeObrasExigeTecnicoEmEdificacoes() {
        // O nome do cargo não diz "Técnico"; o requisito sim.
        CargoAnalisado c = cargo(frontin, "Fiscal de Obras");
        assertThat(c.classificacao()).isEqualTo(Classificacao.COMPATIVEL);
        assertThat(c.formacoes()).extracting(f -> f.entrada().nome()).contains("Técnico em Edificações");
        assertThat(cargo(frontin, "Enfermagem").classificacao()).isEqualTo(Classificacao.NAO_COMPATIVEL);
    }

    @Test
    void frontinDataDaProva() {
        // "11.1. As provas objetivas serão realizadas na data provável de 29 de novembro de 2026"
        assertThat(frontin.concurso().dataProva().valor()).isEqualTo(LocalDate.of(2026, 11, 29));
    }
}
