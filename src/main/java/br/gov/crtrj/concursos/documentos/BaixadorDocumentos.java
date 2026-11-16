package br.gov.crtrj.concursos.documentos;

import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Baixa um documento (PDF) de uma URL, com timeout, limite de tamanho e checagem de que o
 * conteúdo é mesmo um PDF. Não contorna captcha nem bloqueio anti-bot: se a fonte recusar,
 * o erro sobe e o documento deve ser enviado manualmente.
 */
public final class BaixadorDocumentos {

    public record Download(byte[] conteudo, String urlFinal, String contentType) {
    }

    public static class FalhaDownload extends IOException {
        public FalhaDownload(String mensagem) {
            super(mensagem);
        }
    }

    private final long tamanhoMaximo;
    private final Duration timeout;
    private final String userAgent;
    /** Criado no primeiro download: a aplicação não precisa de rede para subir. */
    private volatile HttpClient http;

    public BaixadorDocumentos(long tamanhoMaximoBytes, Duration timeout, String userAgent) {
        this.tamanhoMaximo = tamanhoMaximoBytes;
        this.timeout = timeout;
        this.userAgent = userAgent;
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

    public static BaixadorDocumentos padrao() {
        return new BaixadorDocumentos(60L * 1024 * 1024, Duration.ofSeconds(90), "ConcursosTecnicosBot/0.1");
    }

    public Download baixar(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new FalhaDownload("URL inválida: " + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new FalhaDownload("Só são aceitas URLs http/https: " + url);
        }
        HttpRequest requisicao = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/pdf,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<InputStream> resposta;
        try {
            resposta = http().send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaDownload("Download interrompido: " + url);
        }
        if (resposta.statusCode() >= 400) {
            resposta.body().close();
            throw new FalhaDownload("A fonte respondeu HTTP " + resposta.statusCode() + " para " + url
                    + ". Se o site bloqueia acesso automatizado, envie o PDF manualmente.");
        }
        byte[] conteudo = lerComLimite(resposta.body());
        if (!LeitorPdf.pareceSerPdf(conteudo)) {
            throw new FalhaDownload("O conteúdo de " + url + " não é um PDF (content-type: "
                    + resposta.headers().firstValue("Content-Type").orElse("?") + "). Informe o link direto do arquivo.");
        }
        return new Download(conteudo, resposta.uri().toString(), resposta.headers().firstValue("Content-Type").orElse(null));
    }

    private byte[] lerComLimite(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int lidos;
            while ((lidos = in.read(buffer)) != -1) {
                if (saida.size() + lidos > tamanhoMaximo) {
                    throw new FalhaDownload("Documento maior que o limite de " + (tamanhoMaximo / (1024 * 1024)) + " MB");
                }
                saida.write(buffer, 0, lidos);
            }
            return saida.toByteArray();
        }
    }
}
