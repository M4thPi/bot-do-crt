package br.gov.crtrj.concursos.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentação interativa em {@code /swagger-ui.html}: o botão "Authorize" recebe a chave da API. */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA_CHAVE = "chaveApi";

    @Bean
    OpenAPI documentacao() {
        return new OpenAPI()
                .info(new Info().title("Concursos para Técnicos Industriais — API").version("v1")
                        .description("Vagas de concursos públicos para Técnicos Industriais, só com requisitos confirmados"
                                + " no edital oficial. GET /api/v1/publico/** é aberto e traz apenas o que o revisor aprovou;"
                                + " todo o resto exige a chave no cabeçalho X-API-Key e deve ser chamado pelo backend do site."))
                .components(new Components().addSecuritySchemes(ESQUEMA_CHAVE, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(FiltroChaveApi.CABECALHO)))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_CHAVE));
    }
}
