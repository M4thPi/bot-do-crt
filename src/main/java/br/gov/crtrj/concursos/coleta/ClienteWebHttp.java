package br.gov.crtrj.concursos.coleta;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente HTTP educado: identifica o robô no User-Agent, respeita o robots.txt de cada site,
 * espera um intervalo mínimo entre acessos ao mesmo site, limita o tamanho das respostas e nunca
 * desliga a verificação de certificado — site com certificado inválido vira erro com motivo, para
 * revisão manual.
 */
public final class ClienteWebHttp implements ClienteWeb {

    public static class CertificadoInvalido extends IOException {
        public CertificadoInvalido(String url, Throwable causa) {
            super("Certificado de segurança inválido em " + url + " (o site não pôde ser verificado). "
                    + "Baixe o edital manualmente pelo navegador.", causa);
        }
    }

    private static final Duration VALIDADE_ROBOTS = Duration.ofHours(24);
    private static final Duration VALIDADE_ROBOTS_FORA_DO_AR = Duration.ofHours(1);

    private record RobotsEmCache(RegrasRobots regras, long expiraEm) {
    }

    private final String userAgent;
    private final String nomeRobo;
    private final Duration timeout;
    private final long tamanhoMaximo;
    private final long intervaloPorSiteMs;
    private final List<String> semAcessoAutomatico;
    private final Map<String, Long> ultimoAcesso = new ConcurrentHashMap<>();
    private final Map<String, RobotsEmCache> robots = new ConcurrentHashMap<>();
    private volatile HttpClient http;

    public ClienteWebHttp(String userAgent, Duration timeout, long tamanhoMaximoBytes, long intervaloPorSiteMs) {
        this(userAgent, timeout, tamanhoMaximoBytes, intervaloPorSiteMs, List.of());
    }

    /**
     * @param semAcessoAutomatico domínios que pedem para não serem acessados por robôs além do que
     *                            o robots.txt diz (ex.: um espelho de arquivos sem robots.txt)
     */
    public ClienteWebHttp(String userAgent, Duration timeout, long tamanhoMaximoBytes, long intervaloPorSiteMs,
                          List<String> semAcessoAutomatico) {
        this.userAgent = userAgent;
        this.nomeRobo = userAgent.split("[/\\s]", 2)[0];
        this.timeout = timeout;
        this.tamanhoMaximo = tamanhoMaximoBytes;
        this.intervaloPorSiteMs = intervaloPorSiteMs;
        this.semAcessoAutomatico = semAcessoAutomatico.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
    }

    private HttpClient http() {
        if (http == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(20))
                            .build();
                }
            }
        }
        return http;
    }

    @Override
    public Resposta get(String url) throws IOException {
        verificarPermissao(url);
        return buscar(url);
    }

    @Override
    public void verificarPermissao(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) {
            return;
        }
        String hostMinusculo = host.toLowerCase(Locale.ROOT);
        for (String dominio : semAcessoAutomatico) {
            if (hostMinusculo.equals(dominio) || hostMinusculo.endsWith("." + dominio)) {
                throw new AcessoNaoPermitido("O site " + host + " não permite que robôs baixem seus arquivos. "
                        + "Abra o link no navegador e envie o PDF.");
            }
        }
        String caminho = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            caminho += "?" + uri.getRawQuery();
        }
        if (!regrasDe(uri).permite(caminho)) {
            throw new AcessoNaoPermitido("O robots.txt de " + host + " não permite robôs em " + caminho
                    + ". Abra o link no navegador e envie o PDF.");
        }
    }

    /** robots.txt do site, guardado por 24 h. Sem arquivo (404) = liberado; site com erro 5xx = bloqueado por 1 h. */
    private RegrasRobots regrasDe(URI uri) {
        String origem = uri.getScheme() + "://" + uri.getRawAuthority();
        RobotsEmCache cache = robots.get(origem);
        if (cache != null && cache.expiraEm() > System.currentTimeMillis()) {
            return cache.regras();
        }
        RegrasRobots regras;
        Duration validade = VALIDADE_ROBOTS;
        try {
            Resposta r = buscar(origem + "/robots.txt");
            String corpo = new String(r.corpo(), StandardCharsets.UTF_8).strip();
            if (r.status() >= 500) {
                regras = RegrasRobots.BLOQUEADO;
                validade = VALIDADE_ROBOTS_FORA_DO_AR;
            } else if (!r.ok() || corpo.startsWith("<")) {
                regras = RegrasRobots.LIBERADO;   // 4xx ou página HTML no lugar do arquivo: o site não tem regras
            } else {
                regras = RegrasRobots.ler(corpo, nomeRobo);
            }
        } catch (IOException e) {
            // sem resposta: o acesso de verdade vai falhar e registrar o erro; tenta o robots.txt de novo em 1 h
            regras = RegrasRobots.LIBERADO;
            validade = VALIDADE_ROBOTS_FORA_DO_AR;
        }
        robots.put(origem, new RobotsEmCache(regras, System.currentTimeMillis() + validade.toMillis()));
        return regras;
    }

    private Resposta buscar(String url) throws IOException {
        URI uri = URI.create(url);
        esperarVez(uri.getHost());
        HttpRequest requisicao = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/pdf;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resposta = http().send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
            byte[] corpo = ler(resposta.body());
            return new Resposta(resposta.uri().toString(), resposta.statusCode(),
                    resposta.headers().firstValue("Content-Type").orElse(null), corpo);
        } catch (SSLHandshakeException e) {
            throw new CertificadoInvalido(url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Acesso interrompido: " + url, e);
        }
    }

    private void esperarVez(String host) {
        if (host == null) {
            return;
        }
        synchronized (ultimoAcesso) {
            long agora = System.currentTimeMillis();
            long espera = ultimoAcesso.getOrDefault(host, 0L) + intervaloPorSiteMs - agora;
            if (espera > 0) {
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ultimoAcesso.put(host, System.currentTimeMillis());
        }
    }

    private byte[] ler(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int lidos;
            while ((lidos = in.read(buffer)) != -1) {
                if (saida.size() + lidos > tamanhoMaximo) {
                    throw new IOException("Resposta maior que o limite de " + (tamanhoMaximo / (1024 * 1024)) + " MB");
                }
                saida.write(buffer, 0, lidos);
            }
            return saida.toByteArray();
        }
    }
}
