package br.gov.crtrj.concursos.coleta.dou;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * Acesso ao INLABS (https://inlabs.in.gov.br): login com o e-mail e a senha do cadastro e download
 * do pacote .zip de cada edição ("2026-10-01-DO3.zip"). A senha só vai no corpo do login, nunca
 * em log nem em mensagem de erro.
 */
public final class ClienteInlabsHttp implements FonteDou.Inlabs {

    private static final String LOGIN = "https://inlabs.in.gov.br/logar.php";
    private static final String DOWNLOAD = "https://inlabs.in.gov.br/index.php?p=%s&dl=%s-%s.zip";
    private static final String COOKIE_SESSAO = "inlabs_session_cookie";
    private static final long TAMANHO_MAXIMO = 400L * 1024 * 1024;

    private final String email;
    private final String senha;
    private final String userAgent;
    private final Duration timeout;
    private final CookieManager cookies = new CookieManager();
    private volatile HttpClient http;
    private volatile boolean logado;

    public ClienteInlabsHttp(String email, String senha, String userAgent, Duration timeout) {
        this.email = email;
        this.senha = senha;
        this.userAgent = userAgent;
        this.timeout = timeout;
    }

    private HttpClient http() {
        if (http == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(20))
                            .cookieHandler(cookies)
                            .build();
                }
            }
        }
        return http;
    }

    @Override
    public synchronized int lerEdicao(LocalDate data, String secao, Consumer<AtoDou> atos) throws IOException {
        if (!logado) {
            entrar();
        }
        HttpResponse<InputStream> r = baixar(data, secao);
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            r.body().close();
            logado = false;   // sessão expirou: entra de novo uma vez
            entrar();
            r = baixar(data, secao);
        }
        if (r.statusCode() == 404) {
            r.body().close();
            return -1;
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            r.body().close();
            throw new IOException("O INLABS respondeu HTTP " + r.statusCode() + " ao baixar a edição " + data + " " + secao + ".");
        }
        try (BufferedInputStream in = new BufferedInputStream(new Limitado(r.body(), TAMANHO_MAXIMO))) {
            in.mark(2);
            int p = in.read();
            int k = in.read();
            in.reset();
            if (p != 'P' || k != 'K') {
                // página HTML no lugar do .zip: sessão perdida ou edição indisponível
                logado = false;
                throw new IOException("O INLABS não devolveu o arquivo da edição " + data + " " + secao
                        + " (sessão expirada ou edição indisponível). O robô tenta de novo na próxima rodada.");
            }
            return LeitorAtosDou.ler(in, data, secao, atos);
        }
    }

    private void entrar() throws IOException {
        String corpo = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(senha, StandardCharsets.UTF_8);
        HttpRequest login = HttpRequest.newBuilder(URI.create(LOGIN))
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        try {
            http().send(login, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Login no INLABS interrompido.", e);
        }
        boolean temSessao = cookies.getCookieStore().getCookies().stream()
                .anyMatch(c -> COOKIE_SESSAO.equals(c.getName()) && !c.getValue().isBlank());
        if (!temSessao) {
            throw new IOException("O INLABS recusou o login: confira o e-mail e a senha nas variáveis "
                    + "CONCURSOS_INLABS_EMAIL e CONCURSOS_INLABS_SENHA.");
        }
        logado = true;
    }

    private HttpResponse<InputStream> baixar(LocalDate data, String secao) throws IOException {
        String dia = data.toString();
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(DOWNLOAD.formatted(dia, dia, secao)))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", userAgent)
                .header("origem", "736372697074")   // pedido pela Imprensa Nacional em downloads por script
                .GET()
                .build();
        try {
            return http().send(pedido, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download do INLABS interrompido.", e);
        }
    }

    /** Interrompe a leitura se o pacote passar do limite. */
    private static final class Limitado extends FilterInputStream {
        private final long limite;
        private long lidos;

        Limitado(InputStream in, long limite) {
            super(in);
            this.limite = limite;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                contar(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                contar(n);
            }
            return n;
        }

        private void contar(long n) throws IOException {
            lidos += n;
            if (lidos > limite) {
                throw new IOException("Pacote do INLABS maior que " + (limite / (1024 * 1024)) + " MB.");
            }
        }
    }
}
