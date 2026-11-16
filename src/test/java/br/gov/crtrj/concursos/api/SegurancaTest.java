package br.gov.crtrj.concursos.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Só a leitura pública é aberta; o resto exige a chave, que o navegador de outro site nunca consegue usar. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-seguranca;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-seguranca",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=" + SegurancaTest.CHAVE,
        "concursos.api.origens-permitidas=https://meusite.org.br/"})
@AutoConfigureMockMvc
class SegurancaTest {

    static final String CHAVE = "chave-da-seguranca-com-mais-de-24-caracteres";
    private static final String SITE = "https://meusite.org.br";

    @Autowired
    MockMvc mvc;

    @Test
    void semChaveTudoQueNaoEPublicoDa401EmJson() throws Exception {
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "ApiKey"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Não autenticado"))
                .andExpect(jsonPath("$.detail", containsString("X-API-Key")));

        mvc.perform(get("/api/v1/cargos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/revisao/fila")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/documentos/1/arquivo")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/cargos/1/revisao").contentType(MediaType.APPLICATION_JSON).content("{\"decisao\":\"APROVADA\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/coletas")).andExpect(status().isUnauthorized());
        // escrever nunca é público, nem na rota pública
        mvc.perform(post("/api/v1/publico/vagas")).andExpect(status().isUnauthorized());
        // não há página nenhuma: o painel antigo saiu
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/revisao")).andExpect(status().isUnauthorized());
    }

    @Test
    void chaveErradaNaoPassa() throws Exception {
        mvc.perform(get("/api/v1/status").header("X-API-Key", "errada")).andExpect(status().isUnauthorized());
        // um pedaço da chave certa também não
        mvc.perform(get("/api/v1/status").header("X-API-Key", CHAVE.substring(0, CHAVE.length() - 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/status").header("X-API-Key", CHAVE + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/status").header("Authorization", "Bearer " + CHAVE)).andExpect(status().isUnauthorized());
    }

    @Test
    void comAChaveCertaPassa() throws Exception {
        mvc.perform(get("/api/v1/status").header("X-API-Key", CHAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concursos").value(0));
    }

    @Test
    void leituraPublicaESaudeNaoPedemChave() throws Exception {
        mvc.perform(get("/api/v1/publico/vagas")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // a documentação mostra como mandar a chave, e a rota pública aparece sem cadeado
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("X-API-Key")))
                .andExpect(jsonPath("$.paths['/api/v1/publico/vagas'].get.security").isEmpty());
    }

    @Test
    void corsSoNaLeituraPublicaEParaAsOrigensConfiguradas() throws Exception {
        // origem configurada (a barra final da configuração é ignorada)
        mvc.perform(get("/api/v1/publico/vagas").header(HttpHeaders.ORIGIN, SITE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SITE));
        mvc.perform(options("/api/v1/publico/vagas").header(HttpHeaders.ORIGIN, SITE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SITE));

        // outro site não lê
        mvc.perform(get("/api/v1/publico/vagas").header(HttpHeaders.ORIGIN, "https://outro-site.com"))
                .andExpect(status().isForbidden());

        // as rotas com chave não têm CORS: nem o site configurado as lê do navegador
        mvc.perform(get("/api/v1/revisao/fila").header(HttpHeaders.ORIGIN, SITE).header("X-API-Key", CHAVE))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options("/api/v1/revisao/fila").header(HttpHeaders.ORIGIN, SITE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void respostaDeErroNaoVazaDetalheInterno() throws Exception {
        mvc.perform(get("/api/v1/cargos/abc").header("X-API-Key", CHAVE))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("java.lang"))))
                .andExpect(content().string(not(containsString("br.gov.crtrj"))));
    }
}
