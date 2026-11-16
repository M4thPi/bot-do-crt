package br.gov.crtrj.concursos.motor.evidencia;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.StatusCampo;
import br.gov.crtrj.concursos.motor.extracao.ParserValores;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Última barreira antes de um dado ser aceito: o trecho citado precisa existir na página
 * informada (ou vizinha) e o valor precisa poder ser lido do próprio trecho. Vale para qualquer
 * extrator — regex hoje, IA depois.
 */
public final class VerificadorEvidencias {

    private static final int MAX_PADRAO = 500;

    public <T> Campo<T> verificar(TextoDocumento texto, Campo<T> campo) {
        if (campo.status() == StatusCampo.NAO_ENCONTRADO) {
            return campo;
        }
        Evidencia evidencia = campo.evidencia();
        Evidencia pontuada = evidencia.comScore(pontuar(texto, evidencia));
        if (!pontuada.verificada() || !valorConfere(campo.valor(), evidencia.trecho())) {
            return Campo.naoEncontrado();
        }
        return new Campo<>(campo.valor(), campo.status(), pontuada);
    }

    public Evidencia verificar(TextoDocumento texto, Evidencia evidencia) {
        if (evidencia == null) {
            return null;
        }
        Evidencia pontuada = evidencia.comScore(pontuar(texto, evidencia));
        return pontuada.verificada() ? pontuada : null;
    }

    public double pontuar(TextoDocumento texto, Evidencia evidencia) {
        String alvo = Normalizador.compacto(evidencia.trecho());
        int pagina = evidencia.pagina();
        if (alvo.isEmpty() || pagina < 1 || pagina > texto.totalPaginas()) {
            return 0;
        }
        int de = Math.max(1, pagina - 1);
        int ate = Math.min(texto.totalPaginas(), pagina + 1);
        String janela = Normalizador.compacto(
                texto.original().substring(texto.inicioDaPagina(de), texto.fimDaPagina(ate)));
        if (janela.contains(alvo)) {
            return 1.0;
        }
        return similaridadeParcial(alvo.length() > MAX_PADRAO ? alvo.substring(0, MAX_PADRAO) : alvo, janela);
    }

    /** O valor precisa aparecer no trecho — evita, por exemplo, R$ 4.520 com trecho dizendo R$ 4.250. */
    boolean valorConfere(Object valor, String trecho) {
        String norm = Normalizador.normalizar(trecho);
        if (valor instanceof BigDecimal numero) {
            return ParserValores.valores(norm, 0, norm.length()).stream()
                    .anyMatch(v -> v.valor().compareTo(numero) == 0);
        }
        if (valor instanceof LocalDate data) {
            return ParserValores.datas(norm, 0, norm.length()).stream().anyMatch(d -> d.data().equals(data));
        }
        if (valor instanceof Integer inteiro) {
            return Pattern.compile("(?<!\\d)" + inteiro + "(?!\\d)").matcher(norm).find();
        }
        // Textos podem ser literais ou rótulos derivados ("Municipal"); a existência do trecho já foi checada.
        return true;
    }

    /** 1 - (distância de edição mínima do padrão contra qualquer substring do texto) / |padrão|. */
    static double similaridadeParcial(String padrao, String texto) {
        int m = padrao.length();
        if (m == 0) {
            return 0;
        }
        int[] anterior = new int[m + 1];
        int[] atual = new int[m + 1];
        for (int i = 0; i <= m; i++) {
            anterior[i] = i;
        }
        int melhor = m;
        for (int j = 1; j <= texto.length(); j++) {
            atual[0] = 0;
            char c = texto.charAt(j - 1);
            for (int i = 1; i <= m; i++) {
                int custo = padrao.charAt(i - 1) == c ? 0 : 1;
                atual[i] = Math.min(Math.min(atual[i - 1] + 1, anterior[i] + 1), anterior[i - 1] + custo);
            }
            melhor = Math.min(melhor, atual[m]);
            int[] troca = anterior;
            anterior = atual;
            atual = troca;
        }
        return 1.0 - (double) melhor / m;
    }
}
