package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.motor.dominio.Classificacao;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.AlteracaoLinha;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
import br.gov.crtrj.concursos.servico.ServicoRevisao;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Etapa 4: o robô volta à página oficial, acha a retificação e propõe as mudanças; o revisor decide. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-acompanhamento;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-acompanhamento",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=chave-de-teste-com-mais-de-24-caracteres"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcompanhamentoTest {

    private static final String PAGINA = "https://www.niteroi.rj.gov.br/concursos";
    private static final String EDITAL = "https://www.niteroi.rj.gov.br/editais/01-2026.pdf";
    private static final String RETIFICACAO = "https://www.niteroi.rj.gov.br/editais/retificacao-01-edital-01-2026.pdf";
    private static final String GABARITO = "https://www.niteroi.rj.gov.br/editais/gabarito-edital-01-2026.pdf";

    private static final byte[] PDF_RETIFICACAO = EditalSintetico.pdf(List.of(List.of(
            "PREFEITURA MUNICIPAL DE NITERÓI",
            "EDITAL DE RETIFICAÇÃO Nº 01 AO EDITAL Nº 01/2026",
            "1. A Prefeitura torna pública a prorrogação das inscrições, que ficam prorrogadas até 22/10/2026.",
            "2. Fica retificado o requisito do cargo TÉCNICO DE MANUTENÇÃO – ELETROTÉCNICA:",
            "Onde se lê: Certificado de conclusão de Curso Técnico em Eletrotécnica",
            "Leia-se: Certificado de conclusão de Curso Técnico em Eletrotécnica ou em Eletromecânica",
            "3. As demais disposições do edital permanecem inalteradas.")));

    /** 29/09/2026: inscrições abertas (10/09 a 15/10/2026), prova em 29/11/2026. */
    @TestConfiguration
    static class Ambiente {
        @Bean
        @Primary
        Clock relogioFixo() {
            return Clock.fixed(LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }

        @Bean
        @Primary
        ClienteWeb webFalsa() {
            return new WebFalsa().html(PAGINA, """
                    <h1>Concurso Público – Edital nº 01/2026</h1>
                    <a href="/editais/01-2026.pdf">Edital nº 01/2026 – Abertura</a>
                    <a href="/editais/retificacao-01-edital-01-2026.pdf">Retificação nº 01 – Edital 01/2026</a>
                    <a href="/editais/gabarito-edital-01-2026.pdf">Gabarito preliminar – Edital 01/2026</a>""");
        }
    }

    @MockitoBean
    BaixadorDocumentos baixador;

    @Autowired
    ServicoIngestao ingestao;

    @Autowired
    ServicoRevisao revisao;

    @Autowired
    ServicoAcompanhamento acompanhamento;

    @Autowired
    ConcursoRepositorio concursos;

    @Autowired
    CargoRepositorio cargos;

    @Autowired
    AlteracaoRepositorio alteracoes;

    @Autowired
    MockMvc mvc;

    private long concursoId;
    private long manutencao;

    @BeforeAll
    void editalAnalisadoEAprovado() throws Exception {
        ServicoIngestao.Resumo r = ingestao.ingerirArquivo(EditalSintetico.prefeitura(), "edital.pdf", EDITAL,
                TipoDocumento.EDITAL, false);
        concursoId = r.concursoId();
        manutencao = r.cargos().stream().filter(c -> c.nome().contains("MANUTEN")).findFirst().orElseThrow().id();
        revisao.aprovar(manutencao, Classificacao.COMPATIVEL, null, Set.of(), null);
        concursos.definirPaginaOficial(concursoId, PAGINA, false);
    }

    private List<AlteracaoLinha> pendentes(AlteracaoRepositorio.Tipo tipo) {
        return alteracoes.pendentes().stream().filter(a -> tipo.name().equals(a.tipo())).toList();
    }

    @Test
    @Order(1)
    void achaARetificacaoEPropoeAsMudancas() throws IOException {
        when(baixador.baixar(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (!url.equals(RETIFICACAO)) {
                throw new IOException("URL inesperada: " + url);
            }
            return new BaixadorDocumentos.Download(PDF_RETIFICACAO, url, "application/pdf");
        });

        ServicoAcompanhamento.Resumo resumo = acompanhamento.acompanharPendentes();

        assertThat(resumo.concursosVisitados()).isEqualTo(1);
        assertThat(resumo.documentosNovos()).isEqualTo(1);   // o edital já é conhecido; gabarito não interessa
        verify(baixador, never()).baixar(GABARITO);
        verify(baixador, never()).baixar(EDITAL);

        assertThat(pendentes(AlteracaoRepositorio.Tipo.NOVO_DOCUMENTO)).extracting(AlteracaoLinha::depois)
                .containsExactly("EDITAL DE RETIFICAÇÃO Nº 01 AO EDITAL Nº 01/2026");
        AlteracaoLinha prazo = pendentes(AlteracaoRepositorio.Tipo.PRAZO_INSCRICAO).getFirst();
        assertThat(prazo.antes()).isEqualTo("15/10/2026");
        assertThat(prazo.depois()).isEqualTo("22/10/2026");
        assertThat(prazo.trecho()).contains("prorrogadas até 22/10/2026");
        assertThat(prazo.pagina()).isEqualTo(1);
        assertThat(pendentes(AlteracaoRepositorio.Tipo.TRECHO_ALTERADO)).extracting(AlteracaoLinha::depois)
                .containsExactly("Certificado de conclusão de Curso Técnico em Eletrotécnica ou em Eletromecânica");
        assertThat(pendentes(AlteracaoRepositorio.Tipo.CARGO_CITADO)).extracting(AlteracaoLinha::cargoId)
                .containsExactly(manutencao);
        // nada muda sozinho
        assertThat(concursos.porId(concursoId).orElseThrow().inscricaoFim()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    @Order(2)
    void segundaVisitaNaoRepete() {
        long antes = alteracoes.contarPendentes();
        ServicoAcompanhamento.Resumo resumo = acompanhamento.acompanharAgora(concursoId);
        assertThat(resumo.documentosNovos()).isZero();
        assertThat(alteracoes.contarPendentes()).isEqualTo(antes);
        // visita recente: a rodada normal não volta lá antes do intervalo
        assertThat(acompanhamento.acompanharPendentes().concursosVisitados()).isZero();
    }

    @Test
    @Order(3)
    @WithMockUser(roles = "API")
    void revisorVeEAplica() throws Exception {
        mvc.perform(get("/api/v1/revisao/alteracoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendentes").value(4))
                .andExpect(jsonPath("$.grupos[0].itens[?(@.rotulo == 'Prazo de inscrição')].antes", hasItem("15/10/2026")))
                .andExpect(jsonPath("$.grupos[0].itens[?(@.rotulo == 'Prazo de inscrição')].depois", hasItem("22/10/2026")))
                .andExpect(jsonPath("$.grupos[0].itens[?(@.rotulo == 'Prazo de inscrição')].acao", hasItem("APLICAR")))
                .andExpect(jsonPath("$.grupos[0].itens[*].rotulo", hasItem("Onde se lê / leia-se")))
                .andExpect(jsonPath("$.grupos[0].itens[?(@.tipo == 'CARGO_CITADO')].acao", hasItem("CARGO")))
                .andExpect(jsonPath("$.concursos[0].paginaOficial").value(PAGINA))
                .andExpect(jsonPath("$.concursos[0].emAcompanhamento").value(true));

        long prazo = pendentes(AlteracaoRepositorio.Tipo.PRAZO_INSCRICAO).getFirst().id();
        long documento = pendentes(AlteracaoRepositorio.Tipo.NOVO_DOCUMENTO).getFirst().id();
        long trecho = pendentes(AlteracaoRepositorio.Tipo.TRECHO_ALTERADO).getFirst().id();

        mvc.perform(get("/api/v1/publico/vagas").with(anonymous()))
                .andExpect(jsonPath("$.concursos[0].inscricoes").value("10/09/2026 a 15/10/2026"))
                .andExpect(jsonPath("$.concursos[0].documentosPosteriores").isEmpty());

        mvc.perform(post("/api/v1/alteracoes/" + prazo + "/aplicar")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/alteracoes/" + documento + "/aplicar")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/alteracoes/" + trecho + "/aplicar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("só informativa")));
        mvc.perform(post("/api/v1/alteracoes/" + trecho + "/vista")).andExpect(status().isNoContent());
        // já decidida: não vale decidir de novo; id desconhecido é 404
        mvc.perform(post("/api/v1/alteracoes/" + prazo + "/aplicar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("já foi decidida")));
        mvc.perform(post("/api/v1/alteracoes/999999/descartar")).andExpect(status().isNotFound());

        assertThat(concursos.porId(concursoId).orElseThrow().inscricaoFim()).isEqualTo(LocalDate.of(2026, 10, 22));
        mvc.perform(get("/api/v1/publico/vagas").with(anonymous()))
                .andExpect(jsonPath("$.concursos[0].inscricoes").value("10/09/2026 a 22/10/2026"))
                .andExpect(jsonPath("$.concursos[0].inscricaoFim").value("2026-10-22"))
                .andExpect(jsonPath("$.concursos[0].documentosPosteriores[0].url").value(RETIFICACAO))
                .andExpect(jsonPath("$.concursos[0].documentosPosteriores[0].nome",
                        containsString("EDITAL DE RETIFICAÇÃO Nº 01")));
    }

    @Test
    @Order(4)
    @WithMockUser(roles = "API")
    void cargoCitadoVoltaParaRevisao() throws Exception {
        long citado = pendentes(AlteracaoRepositorio.Tipo.CARGO_CITADO).getFirst().id();
        mvc.perform(post("/api/v1/alteracoes/" + citado + "/reabrir-cargo")).andExpect(status().isNoContent());

        assertThat(cargos.porId(manutencao).orElseThrow().revisaoStatus()).isEqualTo("PENDENTE");
        mvc.perform(get("/api/v1/publico/vagas").with(anonymous()))
                .andExpect(jsonPath("$.totalVagas").value(0))
                .andExpect(content().string(not(containsString("TÉCNICO DE MANUTENÇÃO"))));
        assertThat(alteracoes.contarPendentes()).isZero();
    }

    @Test
    @Order(5)
    void sinalDoAgregadorAntecipaAVisita() {
        concursos.registrarAcompanhamento(concursoId, java.time.LocalDateTime.of(2026, 9, 29, 0, 0));
        acompanhamento.registrarSinal(concursoId, "Prazo de inscrição (agregador)", "inscrições até 22/10/2026",
                "inscrições até 29/10/2026", "PCI");

        assertThat(pendentes(AlteracaoRepositorio.Tipo.SINAL_AGREGADOR)).hasSize(1);
        assertThat(concursos.acompanhamento(concursoId).orElseThrow().checarAgora()).isTrue();
        assertThat(acompanhamento.acompanharPendentes().concursosVisitados()).isEqualTo(1);
    }

    @Test
    @Order(6)
    @WithMockUser(roles = "API")
    void revisorInformaAPaginaOficialEOutroDocumento() throws Exception {
        mvc.perform(put("/api/v1/concursos/" + concursoId + "/pagina-oficial").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://exemplo.org/concursos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("começando com https://")));
        mvc.perform(put("/api/v1/concursos/" + concursoId + "/pagina-oficial").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + PAGINA + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(concursos.acompanhamento(concursoId).orElseThrow().paginaOficial()).isEqualTo(PAGINA);
        mvc.perform(put("/api/v1/concursos/999999/pagina-oficial").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + PAGINA + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/concursos/" + concursoId + "/documentos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        // documento enviado como arquivo: o mesmo da retificação, já conhecido, não gera nada novo
        mvc.perform(multipart("/api/v1/concursos/" + concursoId + "/documentos")
                        .file(new MockMultipartFile("arquivo", "retificacao.pdf", "application/pdf", PDF_RETIFICACAO))
                        .param("urlOrigem", RETIFICACAO).param("fonteOficial", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alteracoesParaRevisar").value(0));

        mvc.perform(post("/api/v1/concursos/999999/verificacoes")).andExpect(status().isNotFound());
    }
}
