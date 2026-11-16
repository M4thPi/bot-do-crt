package br.gov.crtrj.concursos.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Só {@code GET /api/v1/publico/**} (cargos já aprovados na revisão) é aberto. Todo o resto exige a chave de API,
 * que fica no backend do site: as rotas com chave não têm CORS, então nenhum navegador consegue chamá-las de outro site.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SegurancaConfig {

    static final int TAMANHO_MINIMO_CHAVE = 24;

    private static final Logger log = LoggerFactory.getLogger(SegurancaConfig.class);

    @Bean
    SecurityFilterChain api(HttpSecurity http, ConcursosProperties props, ObjectMapper json) throws Exception {
        ConcursosProperties.Api config = props.api();
        http.addFilterBefore(new FiltroChaveApi(chaveDaApi(config)), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/v1/publico/**", "/actuator/health", "/actuator/health/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .cors(c -> c.configurationSource(origensPermitidas(config.origensPermitidas())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // sem sessão nem cookie não há o que um site de terceiros aproveitar
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> naoAutenticado(res, json)));
        return http.build();
    }

    /** CORS só na leitura pública, e só para as origens configuradas. */
    private static CorsConfigurationSource origensPermitidas(List<String> origens) {
        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
        if (!origens.isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOriginPatterns(origens.stream().map(o -> o.strip().replaceAll("/+$", "")).toList());
            cors.setAllowedMethods(List.of("GET", "HEAD"));
            cors.setMaxAge(Duration.ofHours(1));
            fonte.registerCorsConfiguration("/api/v1/publico/**", cors);
        }
        return fonte;
    }

    private static void naoAutenticado(HttpServletResponse resposta, ObjectMapper json) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Chave de API ausente ou inválida. Envie o cabeçalho " + FiltroChaveApi.CABECALHO + ".");
        problema.setTitle("Não autenticado");
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(resposta.getWriter(), problema);
    }

    // ------------------------------------------------------------------ a chave

    /** Variável de ambiente/propriedade, ou o arquivo (gerado na 1ª execução). Chave curta não sobe. */
    static String chaveDaApi(ConcursosProperties.Api api) {
        if (api.chave() != null && !api.chave().isBlank()) {
            return conferirTamanho(api.chave().strip(), "A chave definida em CONCURSOS_API_CHAVE");
        }
        Path arquivo = Path.of(api.arquivoChave());
        return conferirTamanho(chaveDoArquivo(arquivo), "A chave do arquivo " + arquivo.toAbsolutePath());
    }

    private static String conferirTamanho(String chave, String origem) {
        if (chave.length() < TAMANHO_MINIMO_CHAVE) {
            throw new IllegalStateException(origem + " tem " + chave.length() + " caracteres; use ao menos "
                    + TAMANHO_MINIMO_CHAVE + " (ex.: openssl rand -base64 32).");
        }
        return chave;
    }

    /** A chave nunca vai para o log: o log costuma ser copiado e colado por aí. */
    private static String chaveDoArquivo(Path arquivo) {
        try {
            if (Files.exists(arquivo)) {
                String chave = Files.readString(arquivo, StandardCharsets.UTF_8).strip();
                if (!chave.isEmpty()) {
                    log.info("Chave da API lida do arquivo {}", arquivo.toAbsolutePath());
                    return chave;
                }
            }
            byte[] aleatorio = new byte[24];
            new SecureRandom().nextBytes(aleatorio);
            String chave = Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
            if (arquivo.getParent() != null) {
                Files.createDirectories(arquivo.getParent());
            }
            Files.writeString(arquivo, chave + System.lineSeparator(), StandardCharsets.UTF_8);
            apenasODono(arquivo);
            log.warn("Chave da API gerada e guardada em {}. Entregue-a só ao backend do site (cabeçalho X-API-Key);"
                    + " para trocar, edite o arquivo ou defina CONCURSOS_API_CHAVE.", arquivo.toAbsolutePath());
            return chave;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler/criar o arquivo da chave da API: " + arquivo, e);
        }
    }

    private static void apenasODono(Path arquivo) {
        try {
            Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException semPermissoesPosix) {
            // Windows: o arquivo herda as permissões da pasta
        }
    }
}
