package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio.PistaLinha;
import br.gov.crtrj.concursos.servico.ServicoIngestao;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O caminho de uma vaga até o site: o robô acha, o revisor decide pela API (com a chave) e só então ela aparece na
 * leitura pública (sem chave).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-fluxo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-fluxo",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=" + FluxoRevisaoTest.CHAVE})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FluxoRevisaoTest {

    static final String CHAVE = "chave-do-fluxo-com-mais-de-24-caracteres";

    private static final String VAGAS = "/api/v1/publico/vagas";
    private static final String EDITAL = "https://www.niteroi.rj.gov.br/editais/01-2026.pdf";

    /** 29/09/2026: inscrições do edital sintético (10/09 a 15/10/2026) abertas. */
    @TestConfiguration
    static class RelogioFixo {
        @Bean
        @Primary
        Clock relogioFixo() {
            return Clock.fixed(LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ServicoIngestao ingestao;

    @Autowired
    CargoRepositorio cargos;

    @Autowired
    PistaRepositorio pistas;

    private long manutencao;
    private long quimica;
    private long enfermagem;

    @BeforeAll
    void ingereEditalOficial() throws Exception {
        ServicoIngestao.Resumo r = ingestao.ingerirArquivo(EditalSintetico.prefeitura(), "edital-niteroi.pdf", EDITAL,
                TipoDocumento.EDITAL, false);
        manutencao = id(r, "MANUTEN");
        quimica = id(r, "QUÍMICA");
        enfermagem = id(r, "ENFERMAGEM");
    }

    private static long id(ServicoIngestao.Resumo r, String trecho) {
        return r.cargos().stream().filter(c -> c.nome().toUpperCase().contains(trecho)).findFirst().orElseThrow().id();
    }

    /** O backend do site: com a chave. */
    private static MockHttpServletRequestBuilder comChave(MockHttpServletRequestBuilder pedido) {
        return pedido.header("X-API-Key", CHAVE);
    }

    private void decidir(long cargo, String json) throws Exception {
        mvc.perform(comChave(post("/api/v1/cargos/" + cargo + "/revisao")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ nada pendente sai

    @Test
    @Order(1)
    void semChaveOPublicoNaoVeNadaPendente() throws Exception {
        mvc.perform(get(VAGAS))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", allOf(containsString("max-age=60"), containsString("public"))))
                .andExpect(jsonPath("$.concursos").value(empty()))
                .andExpect(jsonPath("$.totalVagas").value(0))
                .andExpect(content().string(not(containsString("MANUTENÇÃO"))));
    }

    // ------------------------------------------------------------------ revisão

    @Test
    @Order(2)
    void filaMostraOsPendentesEOCargoVemComOTrechoDoEdital() throws Exception {
        mvc.perform(comChave(get("/api/v1/revisao/fila")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aba").value("pendentes"))
                .andExpect(jsonPath("$.itens[*].cargo", hasItem(containsString("MANUTENÇÃO"))))
                .andExpect(jsonPath("$.itens[*].cargo", hasItem(containsString("QUÍMICA"))))
                // NÃO COMPATÍVEL fica em outra fila
                .andExpect(jsonPath("$.itens[*].cargo", not(hasItem(containsString("ENFERMAGEM")))))
                .andExpect(jsonPath("$.itens", hasSize(3)))
                .andExpect(jsonPath("$.abas[?(@.chave == 'pendentes')].total").value(hasItem(3)))
                .andExpect(jsonPath("$.abas[?(@.chave == 'nao-compativeis')].total").value(hasItem(2)));
        mvc.perform(comChave(get("/api/v1/revisao/fila")).param("aba", "nao-compativeis"))
                .andExpect(jsonPath("$.itens[*].cargo", hasItem(containsString("ENFERMAGEM"))));
        mvc.perform(comChave(get("/api/v1/revisao/fila")).param("aba", "inexistente"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("aba deve ser uma de: pendentes")));

        mvc.perform(comChave(get("/api/v1/revisao/cargos/" + manutencao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenciasCargo[?(@.rotulo == 'Requisito do cargo')].trecho",
                        hasItem(containsString("Curso Técnico em Eletrotécnica"))))
                .andExpect(jsonPath("$.evidenciasCargo[?(@.rotulo == 'Salário-base')].valor", hasItem("R$ 4.250,00")))
                .andExpect(jsonPath("$.paginaInicial").value(1))
                .andExpect(jsonPath("$.documento.url").value(EDITAL))
                // caminho do servidor não sai pela API
                .andExpect(jsonPath("$.documento.arquivoPath").doesNotExist())
                .andExpect(jsonPath("$.previa[?(@.chave == 'SALARIO_BASE')].oculto").value(hasItem(false)))
                .andExpect(jsonPath("$.publicaveis", hasSize(2)));

        mvc.perform(comChave(get("/api/v1/revisao/cargos/999999")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Cargo 999999 não encontrado"));
    }

    @Test
    @Order(3)
    void aprovarPublicaNaLeituraPublica() throws Exception {
        decidir(manutencao, "{\"decisao\":\"APROVADA\",\"classificacao\":\"COMPATIVEL\",\"observacao\":\"Conferido no PDF\"}");

        mvc.perform(get(VAGAS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVagas").value(1))
                .andExpect(jsonPath("$.formacoes[0]").value("Técnico em Eletrotécnica"))
                .andExpect(jsonPath("$.concursos[0].situacao").value("INSCRICOES_ABERTAS"))
                .andExpect(jsonPath("$.concursos[0].situacaoRotulo").value("Inscrições abertas"))
                .andExpect(jsonPath("$.concursos[0].emAberto").value(true))
                .andExpect(jsonPath("$.concursos[0].inscricaoFim").value("2026-10-15"))
                .andExpect(jsonPath("$.concursos[0].editalUrl").value(EDITAL))
                .andExpect(jsonPath("$.concursos[0].vagas[0].cargo").value("TÉCNICO DE MANUTENÇÃO – ELETROTÉCNICA"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].classificacao").value("COMPATIVEL"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].classificacaoRotulo").value("COMPATÍVEL"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].ajustadaNaRevisao").value(false))
                .andExpect(jsonPath("$.concursos[0].vagas[0].salarioBase").value("R$ 4.250,00"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].remuneracaoTotal").value("R$ 5.180,00"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].formacoes[0]").value("Técnico em Eletrotécnica"))
                .andExpect(jsonPath("$.concursos[0].vagas[0].requisito.local", containsString("p. 1")))
                .andExpect(jsonPath("$.concursos[0].vagas[0].conselhoTrecho.texto",
                        containsString("respectivo Conselho Regional dos Técnicos Industriais")))
                .andExpect(jsonPath("$.concursos[0].vagas[0].conselho",
                        hasItem("O edital cita: “Conselho Regional dos Técnicos Industriais”")))
                .andExpect(jsonPath("$.concursos[0].vagas[0].conselho", hasItem("Exige especificamente o CRT-RJ: Não informado")))
                .andExpect(content().string(not(containsString("QUÍMICA"))))
                // a observação é interna; o público e a interface do site não a veem
                .andExpect(content().string(not(containsString("Conferido no PDF"))));

        mvc.perform(get(VAGAS).param("formacao", "Técnico em Química"))
                .andExpect(jsonPath("$.totalVagas").value(0));
        mvc.perform(get(VAGAS).param("busca", "manutencao"))
                .andExpect(jsonPath("$.totalVagas").value(1));
        mvc.perform(get(VAGAS).param("situacao", "encerradas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("abertas")));
    }

    @Test
    @Order(4)
    void mudarAClassificacaoDoMotorExigeJustificativa() throws Exception {
        mvc.perform(comChave(post("/api/v1/cargos/" + enfermagem + "/revisao")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisao\":\"APROVADA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Só é possível publicar como COMPATÍVEL")));
        mvc.perform(comChave(post("/api/v1/cargos/" + quimica + "/revisao")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisao\":\"APROVADA\",\"classificacao\":\"COMPATIVEL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("escreva a justificativa")));
        assertThat(cargos.porId(quimica).orElseThrow().revisaoStatus()).isEqualTo("PENDENTE");

        decidir(quimica, "{\"decisao\":\"APROVADA\",\"classificacao\":\"COMPATIVEL\","
                + "\"justificativa\":\"Item 2.5 (p. 1) exige Curso Técnico em Química.\"}");
        mvc.perform(get(VAGAS))
                .andExpect(jsonPath("$.concursos[0].vagas[?(@.cargo == 'TÉCNICO EM QUÍMICA')].justificativa",
                        hasItem("Item 2.5 (p. 1) exige Curso Técnico em Química.")))
                .andExpect(jsonPath("$.concursos[0].vagas[?(@.cargo == 'TÉCNICO EM QUÍMICA')].ajustadaNaRevisao", hasItem(true)))
                .andExpect(jsonPath("$.concursos[0].vagas[?(@.cargo == 'TÉCNICO EM QUÍMICA')].classificacaoMotor",
                        hasItem("POSSIVELMENTE_COMPATIVEL")));
    }

    @Test
    @Order(5)
    void rejeitarEDesfazerTiramDoPublico() throws Exception {
        decidir(quimica, "{\"decisao\":\"PENDENTE\"}");
        mvc.perform(get(VAGAS)).andExpect(content().string(not(containsString("TÉCNICO EM QUÍMICA"))));

        decidir(quimica, "{\"decisao\":\"REJEITADA\",\"observacao\":\"Não é industrial\"}");
        assertThat(cargos.porId(quimica).orElseThrow().revisaoStatus()).isEqualTo("REJEITADA");
        mvc.perform(get(VAGAS)).andExpect(content().string(not(containsString("TÉCNICO EM QUÍMICA"))));
        mvc.perform(comChave(get("/api/v1/revisao/fila")).param("aba", "rejeitadas"))
                .andExpect(jsonPath("$.itens[*].cargo", hasItem(containsString("QUÍMICA"))));

        mvc.perform(comChave(post("/api/v1/cargos/" + quimica + "/revisao")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisao\":\"TALVEZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @Order(6)
    void dadoExtraidoErradoPodeSerOcultadoNaAprovacao() throws Exception {
        decidir(manutencao, "{\"decisao\":\"PENDENTE\"}");
        mvc.perform(comChave(post("/api/v1/cargos/" + manutencao + "/revisao")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisao\":\"APROVADA\",\"classificacao\":\"COMPATIVEL\",\"ocultar\":[\"INEXISTENTE\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Campo desconhecido")));

        decidir(manutencao, "{\"decisao\":\"APROVADA\",\"classificacao\":\"COMPATIVEL\",\"ocultar\":[\"SALARIO_BASE\",\"PROVA\"]}");
        assertThat(cargos.porId(manutencao).orElseThrow().camposOcultos()).isEqualTo("SALARIO_BASE,PROVA");
        mvc.perform(comChave(get("/api/v1/revisao/cargos/" + manutencao)))
                .andExpect(jsonPath("$.previa[?(@.chave == 'SALARIO_BASE')].oculto").value(hasItem(true)));
        mvc.perform(get(VAGAS))
                .andExpect(jsonPath("$.concursos[0].vagas[0].salarioBase").value("Confira no edital."))
                .andExpect(jsonPath("$.concursos[0].prova").value("Confira no edital."))
                .andExpect(content().string(not(containsString("R$ 4.250,00"))))   // salário-base oculto
                .andExpect(content().string(not(containsString("29/11/2026"))))    // data da prova oculta
                .andExpect(jsonPath("$.concursos[0].vagas[0].remuneracaoTotal").value("R$ 5.180,00"));   // o resto continua
    }

    // ------------------------------------------------------------------ pistas travadas

    @Test
    @Order(7)
    void pistaTravadaRecebeOPdfEnviadoPeloRevisor() throws Exception {
        LocalDateTime agora = LocalDateTime.of(2026, 9, 29, 10, 0);
        long certificado = pistas.inserir(new PistaLinha(null, "PCI", "https://www.pciconcursos.com.br/noticias/ufrrj",
                "UFRRJ abre concurso", "UFRRJ", "RJ", "13 vagas", "Vários Cargos", "Médio / Técnico", "13/10/2026",
                "ERRO_ACESSO", "Certificado de segurança inválido", null, null, null, 1, agora, agora, null));
        long semInteresse = pistas.inserir(new PistaLinha(null, "PCI", "https://www.pciconcursos.com.br/noticias/outro",
                "Outro", "Câmara de Teste", "RJ", "1 vaga", "Técnico", "Médio / Técnico", "01/10/2026",
                "EDITAL_NAO_ENCONTRADO", "Nenhum PDF parece o edital", null, null, null, 1, agora, agora, null));

        mvc.perform(comChave(get("/api/v1/revisao/pistas")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pistas[?(@.orgao == 'UFRRJ')].motivo", hasItem("Certificado de segurança inválido")))
                .andExpect(jsonPath("$.pistas[?(@.orgao == 'UFRRJ')].status", hasItem("ERRO_ACESSO")))
                .andExpect(jsonPath("$.pistas[?(@.orgao == 'UFRRJ')].situacao", hasItem("Site inacessível para o robô")))
                .andExpect(jsonPath("$.pistas[*].orgao", hasItem("Câmara de Teste")))
                .andExpect(jsonPath("$.coleta.pistasAguardando").value(2));

        mvc.perform(comChave(multipart("/api/v1/pistas/" + certificado + "/edital")
                        .file(new MockMultipartFile("arquivo", "edital.pdf", "application/pdf", EditalSintetico.tabela()))
                        .param("urlOrigem", "https://www.ufrrj.br/editais/edital.pdf")
                        .param("fonteOficial", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cargos").isNotEmpty());
        assertThat(pistas.porId(certificado).orElseThrow().status()).isEqualTo("ANALISADA");

        mvc.perform(comChave(post("/api/v1/pistas/" + semInteresse + "/descartar"))).andExpect(status().isNoContent());
        assertThat(pistas.porId(semInteresse).orElseThrow().status()).isEqualTo("DESCARTADA");
        mvc.perform(comChave(post("/api/v1/pistas/999999/descartar"))).andExpect(status().isNotFound());

        // sem o arquivo nem o link, o pedido não faz sentido
        mvc.perform(comChave(multipart("/api/v1/pistas/" + certificado + "/edital")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ------------------------------------------------------------------ erros em JSON

    @Test
    @Order(8)
    void parametrosInvalidosVoltamComoErroDeCliente() throws Exception {
        mvc.perform(comChave(get("/api/v1/cargos")).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("size")));
        mvc.perform(comChave(get("/api/v1/cargos")).param("status", "TALVEZ"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(comChave(get("/api/v1/cargos")).param("classificacao", "OUTRA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("Classificação desconhecida")));
        mvc.perform(comChave(get("/api/v1/rota-que-nao-existe")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(comChave(post("/api/v1/documentos")).contentType(MediaType.APPLICATION_JSON).content("{\"url\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(comChave(post("/api/v1/documentos")).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
