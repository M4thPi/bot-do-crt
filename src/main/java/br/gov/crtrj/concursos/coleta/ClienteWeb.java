package br.gov.crtrj.concursos.coleta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Acesso HTTP usado pela coleta. Interface para os testes poderem simular os sites. */
public interface ClienteWeb {

    /**
     * @param urlFinal     URL depois dos redirecionamentos
     * @param contentType  cabeçalho Content-Type (pode ser null)
     */
    record Resposta(String urlFinal, int status, String contentType, byte[] corpo) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean pdf() {
            return corpo.length > 4 && corpo[0] == '%' && corpo[1] == 'P' && corpo[2] == 'D' && corpo[3] == 'F';
        }

        public String texto() {
            return new String(corpo, StandardCharsets.UTF_8);
        }
    }

    /** O site não permite robôs neste endereço (robots.txt ou pedido do site): só o revisor, pelo navegador. */
    class AcessoNaoPermitido extends IOException {
        public AcessoNaoPermitido(String mensagem) {
            super(mensagem);
        }
    }

    Resposta get(String url) throws IOException;

    /**
     * Confere, sem baixar, se o robô pode acessar o endereço. Usado antes de downloads feitos
     * por outro cliente (ex.: o PDF do edital).
     *
     * @throws AcessoNaoPermitido se o robots.txt do site não permitir
     */
    default void verificarPermissao(String url) throws IOException {
    }
}
