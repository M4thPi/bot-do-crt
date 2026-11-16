package br.gov.crtrj.concursos.api;

import br.gov.crtrj.concursos.EditalSintetico;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=chave-de-teste-com-mais-de-24-caracteres"})
@AutoConfigureMockMvc
@WithMockUser(roles = "API")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTest {

    /** 29/09/2026: inscrições do edital sintético (10/09 a 15/10/2026) estão abertas. */
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

    /** Mesmos bytes nas duas chamadas: o PDFBox gera um ID novo a cada documento. */
    private static final byte[] PDF_OFICIAL = EditalSintetico.prefeitura();

    private static MockMultipartFile arquivo(byte[] pdf) {
        return new MockMultipartFile("arquivo", "edital-niteroi.pdf", "application/pdf", pdf);
    }

    @Test
    @Order(1)
    void enviaEditalEDepoisNaoDuplica() throws Exception {
        mvc.perform(multipart("/api/v1/documentos").file(arquivo(PDF_OFICIAL))
                        .param("urlOrigem", "https://www.niteroi.rj.gov.br/editais/01-2026.pdf"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fonteOficial").value(true))
                .andExpect(jsonPath("$.cargos.length()").value(5))
                .andExpect(jsonPath("$.cargosDescartados").value(1));

        mvc.perform(multipart("/api/v1/documentos").file(arquivo(PDF_OFICIAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jaAnalisado").value(true));
    }

    @Test
    @Order(2)
    void copiaNaoOficialDoMesmoEditalNaoSubstituiAnaliseOficial() throws Exception {
        mvc.perform(multipart("/api/v1/documentos").file(arquivo(EditalSintetico.prefeitura()))
                        .param("urlOrigem", "https://www.pciconcursos.com.br/arquivos/niteroi.pdf"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fonteOficial").value(false))
                .andExpect(jsonPath("$.cargos.length()").value(0))
                .andExpect(jsonPath("$.alteracoes[0]", containsString("análise vigente continua sendo a do documento oficial")));
        mvc.perform(get("/api/v1/concursos/1/documentos")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @Order(3)
    void consultaDoExemploDaEspecificacao() throws Exception {
        // "concursos com inscrições abertas no RJ que aceitem Técnico em Eletrotécnica, salário a partir de R$ 4.000"
        mvc.perform(get("/api/v1/cargos")
                        .param("uf", "RJ")
                        .param("formacao", "Técnico em Eletrotécnica")
                        .param("salarioMin", "4000")
                        .param("status", "INSCRICOES_ABERTAS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].classificacao").value("COMPATIVEL"))
                .andExpect(jsonPath("$.itens[0].remuneracao.salarioBase").value(4250.00))
                .andExpect(jsonPath("$.itens[0].conselho.tipo").value("CRT"))
                .andExpect(jsonPath("$.itens[0].conselho.exigeCrtRj").value("NAO_INFORMADO"))
                .andExpect(jsonPath("$.itens[0].statusConcurso").value("Inscrições abertas"))
                .andExpect(jsonPath("$.itens[0].edital.url").value("https://www.niteroi.rj.gov.br/editais/01-2026.pdf"));
    }

    @Test
    @Order(4)
    void filtrosDeConselhoNaoPresumem() throws Exception {
        mvc.perform(get("/api/v1/cargos").param("aceitaCrt", "true").param("classificacao", "TODAS"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/cargos").param("conselho", "OUTRO").param("classificacao", "TODAS"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/v1/cargos").param("status", "INSCRICOES_ENCERRADAS"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @Order(5)
    void relatorioMarkdownNoFormatoPedido() throws Exception {
        mvc.perform(get("/api/v1/cargos").param("formato", "markdown"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/markdown")))
                .andExpect(content().string(containsString("| Concurso | Órgão | Cargo | Formação | Vagas | Salário | Conselho |")))
                .andExpect(content().string(containsString("**Salário-base:** R$ 4.250,00")))
                .andExpect(content().string(containsString("**Exige especificamente CRT-RJ:** Não informado")))
                .andExpect(content().string(containsString("**Fonte da comprovação:** Edital nº 01/2026, p. 1, item 2.1")))
                .andExpect(content().string(containsString("**Edital oficial:** <https://www.niteroi.rj.gov.br/editais/01-2026.pdf>")));
    }

    @Test
    @Order(6)
    void evidenciasEArquivoGuardado() throws Exception {
        mvc.perform(get("/api/v1/cargos/1/evidencias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].campo", hasItem("formacao")))
                .andExpect(jsonPath("$[?(@.campo == 'salario_base')].trecho", hasItem(containsString("R$ 4.250,00"))));
        mvc.perform(get("/api/v1/documentos/1/arquivo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/v1/cargos/1"))
                .andExpect(jsonPath("$.analise.formacoes[0].evidencia.pagina").value(1));
    }

    @Test
    @Order(7)
    void revisaoHumana() throws Exception {
        mvc.perform(post("/api/v1/cargos/1/revisao").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisao\":\"APROVADA\",\"observacao\":\"Conferido no PDF\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/cargos/1")).andExpect(jsonPath("$.cargo.revisao").value("APROVADA"));
        mvc.perform(get("/api/v1/cargos/999")).andExpect(status().isNotFound());
    }

    @Test
    @Order(8)
    void formacaoDesconhecidaEArquivoQueNaoEhPdf() throws Exception {
        mvc.perform(get("/api/v1/cargos").param("formacao", "astronauta")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/documentos")
                        .file(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", "nao sou pdf".getBytes())))
                .andExpect(status().isBadRequest());
    }
}
