package br.gov.crtrj.concursos.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Reconhece o backend do site pela chave no cabeçalho {@code X-API-Key}. Sem chave válida a requisição segue
 * anônima, e as regras de acesso decidem se a rota é pública.
 */
final class FiltroChaveApi extends OncePerRequestFilter {

    static final String CABECALHO = "X-API-Key";

    private final byte[] esperado;

    FiltroChaveApi(String chave) {
        this.esperado = resumo(chave);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String enviada = request.getHeader(CABECALHO);
        // compara os resumos: o tempo da comparação não depende de quantos caracteres batem
        if (enviada != null && MessageDigest.isEqual(resumo(enviada), esperado)) {
            var contexto = SecurityContextHolder.createEmptyContext();
            contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("site", null,
                    AuthorityUtils.createAuthorityList("ROLE_API")));
            SecurityContextHolder.setContext(contexto);
        }
        chain.doFilter(request, response);
    }

    private static byte[] resumo(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
