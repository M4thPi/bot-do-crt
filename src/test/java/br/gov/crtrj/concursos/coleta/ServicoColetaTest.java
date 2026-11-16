package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.EditalSintetico;
import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.PistaRepositorio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.LocalDate;

import static br.gov.crtrj.concursos.coleta.WebFalsa.linhaPci;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Robô inteiro: listagem → notícia → site oficial → edital → análise → cargos na API. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concursos-coleta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "concursos.armazenamento=target/test-documentos-coleta",
        "concursos.coleta.habilitada=false",
        "concursos.api.chave=chave-de-teste-com-mais-de-24-caracteres",
        "concursos.coleta.listagens-pci=https://www.pciconcursos.com.br/concursos/sudeste/",
        "concursos.coleta.ufs=RJ",
        "concursos.coleta.bancas=",
        "concursos.coleta.portais-de-noticias="})
@AutoConfigureMockMvc
@WithMockUser(roles = "API")
class ServicoColetaTest {

    private static final String NOTICIA_NITEROI = "https://www.pciconcursos.com.br/noticias/prefeitura-de-niteroi-abre-concurso";
    private static final String EDITAL_OFICIAL = "https://www.niteroi.rj.gov.br/concursos/edital-01-2026-abertura.pdf";

    @TestConfiguration
    static class InternetFalsa {
        @Bean
        @Primary
        ClienteWeb webFalsa() {
            return new WebFalsa()
                    .html("https://www.pciconcursos.com.br/concursos/sudeste/", "<html><body>"
                            + linhaPci("da", NOTICIA_NITEROI, "Prefeitura de Niterói", "RJ", "30 vagas até R$ 5.180,00",
                            "Vários Cargos", "Médio / Técnico / Superior", "15/10/2026")
                            + linhaPci("na", "https://www.pciconcursos.com.br/noticias/uerj-professor", "UERJ", "RJ",
                            "1 vaga", "Professor Adjunto", "Superior", "30/10/2026")
                            + "</body></html>")
                    .html(NOTICIA_NITEROI, "<article id=\"noticia\"><p>As inscrições serão feitas pelo site "
                            + "www.niteroi.rj.gov.br/concursos até 15 de outubro.</p>"
                            + "<a class=\"edital-pdf-link\" title=\"EDITAL Nº 01\">EDITAL Nº 01</a></article>")
                    .html("https://www.niteroi.rj.gov.br/concursos",
                            "<a href=\"/concursos/edital-01-2026-abertura.pdf\">Edital nº 01/2026 – Abertura</a>");
        }
    }

    @MockitoBean
    BaixadorDocumentos baixador;

    @Autowired
    ServicoColeta coleta;

    @Autowired
    PistaRepositorio pistas;

    @Autowired
    ConcursoRepositorio concursos;

    @Autowired
    AlteracaoRepositorio alteracoes;

    @Autowired
    ClienteWeb web;

    @Autowired
    MockMvc mvc;

    @Test
    void rodadaCompletaENaoRepeteNaSeguinte() throws Exception {
        when(baixador.baixar(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (!url.equals(EDITAL_OFICIAL)) {
                throw new IOException("URL inesperada: " + url);
            }
            return new BaixadorDocumentos.Download(EditalSintetico.prefeitura(), url, "application/pdf");
        });

        ServicoColeta.ResumoExecucao primeira = coleta.executar().orElseThrow();

        assertThat(primeira.pistasLidas()).isEqualTo(2);
        assertThat(primeira.pistasNovas()).isEqualTo(2);
        assertThat(primeira.editaisAnalisados()).isEqualTo(1);
        assertThat(primeira.erros()).isZero();

        PistaRepositorio.PistaLinha niteroi = pistas.porUrl(NOTICIA_NITEROI).orElseThrow();
        assertThat(niteroi.status()).isEqualTo("ANALISADA");
        assertThat(niteroi.urlOficial()).isEqualTo(EDITAL_OFICIAL);
        assertThat(niteroi.concursoId()).isNotNull();
        assertThat(pistas.porUrl("https://www.pciconcursos.com.br/noticias/uerj-professor").orElseThrow().status())
                .isEqualTo("DESCARTADA");

        // o edital veio do domínio oficial: pode ser COMPATÍVEL (ainda pendente de revisão humana)
        mvc.perform(get("/api/v1/cargos").param("formacao", "eletrotecnica"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].classificacao").value("COMPATIVEL"))
                .andExpect(jsonPath("$.itens[0].edital.url").value(EDITAL_OFICIAL))
                .andExpect(jsonPath("$.itens[0].revisao").value("PENDENTE"));

        ServicoColeta.ResumoExecucao segunda = coleta.executar().orElseThrow();
        assertThat(segunda.pistasNovas()).isZero();
        assertThat(segunda.editaisAnalisados()).isZero();

        mvc.perform(get("/api/v1/pistas").param("status", "ANALISADA")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/admin/coletas")).andExpect(jsonPath("$.ultimas.length()").value(2));

        // acompanhamento: a página onde o edital estava fica guardada para as próximas visitas
        assertThat(concursos.acompanhamento(niteroi.concursoId()).orElseThrow().paginaOficial())
                .isEqualTo("https://www.niteroi.rj.gov.br/concursos");

        // o PCI passa a mostrar outro prazo: vira sinal para o revisor (nunca muda o concurso sozinho)
        ((WebFalsa) web).html("https://www.pciconcursos.com.br/concursos/sudeste/", "<html><body>"
                + linhaPci("da", NOTICIA_NITEROI, "Prefeitura de Niterói", "RJ", "30 vagas até R$ 5.180,00",
                "Vários Cargos", "Médio / Técnico / Superior", "22/10/2026")
                + "</body></html>");
        coleta.executar().orElseThrow();
        assertThat(alteracoes.pendentes()).anyMatch(a -> a.tipo().equals("SINAL_AGREGADOR")
                && a.concursoId().equals(niteroi.concursoId()) && a.depois().contains("22/10/2026"));
        assertThat(concursos.porId(niteroi.concursoId()).orElseThrow().inscricaoFim()).isEqualTo(LocalDate.of(2026, 10, 15));
    }
}
