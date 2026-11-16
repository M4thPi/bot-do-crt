package br.gov.crtrj.concursos.motor.extracao;

import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.gov.crtrj.concursos.motor.texto.Padroes.compilar;

/** Vagas, salário, carga horária, CNH e experiência dentro dos blocos de um cargo. */
public final class ExtratorDadosCargo {

    private static final Pattern ROTULO_BASE = compilar(
            "(?<![a-z])(?:vencimentos?(?: basico)?(?: inicial)?|salario(?:[ -]base| basico| inicial| mensal)?)(?![a-z])");
    private static final Pattern ROTULO_TOTAL = compilar(
            "(?<![a-z])(?:remuneracao(?: inicial| mensal| total| bruta| global)*|subsidio)(?![a-z])");
    private static final Pattern ROTULO_TAXA = compilar("taxa|inscricao|isencao");
    private static final int ALCANCE_VALOR = 80;

    private static final Pattern CARGA_ROTULADA = compilar(
            "(?:carga horaria|jornada(?: de trabalho)?)(?: semanal)?\\s*(?:[:\\-–]|de)?\\s*(\\d{2})\\s*(?:h|horas)(?![a-z])");
    private static final Pattern CARGA_SEMANAL = compilar(
            "(?<!\\d)(\\d{2})\\s*(?:h|horas)\\s*(?:semanais|/\\s*semana|por semana|semanal)");

    private static final Pattern VAGAS_ROTULADAS = compilar(
            "(?:total de vagas|n[uo]mero de vagas|quantidade de vagas|vagas(?: totais| ofertadas)?)\\s*[:\\-–]\\s*(\\d{1,4})(?![\\d%])");
    private static final Pattern VAGAS_NUMERO = compilar(
            "(?<![\\d.,])(\\d{1,4})\\s*(?:\\(\\s*[a-z ]+\\s*\\)\\s*)?vagas?(?![a-z])");
    private static final Pattern VAGAS_IMEDIATAS = compilar(
            "vagas? imediatas?\\s*[:\\-–]?\\s*(\\d{1,4})(?![\\d%])|(?<![\\d.,])(\\d{1,4})\\s*(?:\\(\\s*[a-z ]+\\s*\\)\\s*)?vagas? imediatas?");
    private static final Pattern VAGAS_AMPLA = compilar("ampla concorrencia\\s*[:\\-–]?\\s*(\\d{1,4})(?![\\d%])");
    private static final Pattern VAGAS_PCD = compilar(
            "(?:(?<![a-z])pcd|pessoas? com deficiencia|(?<![a-z])pne(?![a-z])|candidatos? com deficiencia)\\s*[:\\-–]?\\s*(\\d{1,4})(?![\\d%.,])");
    private static final Pattern RESERVA_APOS_NUMERO = compilar("^\\s*(?:\\([a-z ]+\\)\\s*)?vagas?\\s*(?:reservad|para (?:pcd|pessoas com deficiencia|negros|candidatos)|pcd|de cotas)");
    private static final Pattern CADASTRO_RESERVA = compilar("cadastro (?:de )?reserva|(?<![a-z])cr(?![a-z])");

    private static final Pattern CNH = compilar(
            "(?:(?<![a-z])cnh(?![a-z])|carteira nacional de habilitacao)[^.;\\n]{0,60}?(?:categoria|cat\\.?)\\s*[\"'“]?\\s*([a-e](?:\\s*/?\\s*[a-e])?)(?![a-z])"
                    + "|(?<![a-z])cnh\\s*[\"'“(]?\\s*([a-e]{1,2})(?![a-z])");
    private static final Pattern EXPERIENCIA = compilar(
            "experiencia[^.;]{0,80}?(\\d{1,2}|um|uma|dois|duas|tres|seis|doze|dezoito|vinte e quatro)\\s*(?:\\(\\s*[a-z ]+\\s*\\)\\s*)?(?:meses|mes|anos?)(?![a-z])");

    public DadosCargo extrair(TextoDocumento texto, CargoDetectado cargo) {
        List<CargoDetectado.Bloco> blocos = cargo.blocosUteis();
        return new DadosCargo(
                vagasTotal(texto, blocos),
                inteiro(texto, blocos, VAGAS_IMEDIATAS),
                inteiro(texto, blocos, VAGAS_AMPLA),
                inteiro(texto, blocos, VAGAS_PCD),
                cadastroReserva(texto, blocos),
                valorRotulado(texto, blocos, ROTULO_BASE),
                valorRotulado(texto, blocos, ROTULO_TOTAL),
                valorSemRotulo(texto, blocos),
                cargaHoraria(texto, blocos),
                cnh(texto, blocos),
                experiencia(texto, blocos));
    }

    private static Campo<Integer> vagasTotal(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        Campo<Integer> rotulado = inteiro(texto, blocos, VAGAS_ROTULADAS);
        if (rotulado.presente()) {
            return rotulado;
        }
        String norm = texto.normalizado();
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = VAGAS_NUMERO.matcher(norm).region(b.inicioConteudo(), b.fim());
            while (m.find()) {
                String depois = norm.substring(m.start(1) + m.group(1).length(), Math.min(norm.length(), m.end() + 40));
                if (RESERVA_APOS_NUMERO.matcher(depois).find()) {
                    continue;
                }
                return Campo.encontrado(Integer.parseInt(m.group(1)), texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<Integer> inteiro(TextoDocumento texto, List<CargoDetectado.Bloco> blocos, Pattern padrao) {
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = padrao.matcher(texto.normalizado()).region(b.inicioConteudo(), b.fim());
            if (m.find()) {
                int grupo = m.group(1) != null ? 1 : 2;
                return Campo.encontrado(Integer.parseInt(m.group(grupo)),
                        texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<Boolean> cadastroReserva(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = CADASTRO_RESERVA.matcher(texto.normalizado()).region(b.inicioConteudo(), b.fim());
            if (m.find()) {
                return Campo.encontrado(Boolean.TRUE, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    /** Primeiro valor em reais logo depois do rótulo, sem outro rótulo de valor no meio. */
    private static Campo<BigDecimal> valorRotulado(TextoDocumento texto, List<CargoDetectado.Bloco> blocos, Pattern rotulo) {
        String norm = texto.normalizado();
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = rotulo.matcher(norm).region(b.inicioConteudo(), b.fim());
            while (m.find()) {
                int limite = Math.min(b.fim(), m.end() + ALCANCE_VALOR);
                List<ParserValores.ValorEncontrado> valores = ParserValores.valores(norm, m.end(), limite);
                if (valores.isEmpty()) {
                    continue;
                }
                ParserValores.ValorEncontrado v = valores.get(0);
                String entre = norm.substring(m.end(), v.inicio());
                if (ROTULO_BASE.matcher(entre).find() || ROTULO_TOTAL.matcher(entre).find() || ROTULO_TAXA.matcher(entre).find()) {
                    continue;
                }
                return Campo.encontrado(v.valor(), texto.evidencia(m.start(), v.fim(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    /** Valor em reais sem rótulo (típico de linha de tabela): registrado como AMBIGUO. */
    private static Campo<BigDecimal> valorSemRotulo(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        String norm = texto.normalizado();
        for (CargoDetectado.Bloco b : blocos) {
            for (ParserValores.ValorEncontrado v : ParserValores.valores(norm, b.inicioConteudo(), b.fim())) {
                String antes = norm.substring(Math.max(b.inicioConteudo(), v.inicio() - ALCANCE_VALOR), v.inicio());
                if (ROTULO_BASE.matcher(antes).find() || ROTULO_TOTAL.matcher(antes).find() || ROTULO_TAXA.matcher(antes).find()) {
                    continue;
                }
                return Campo.ambiguo(v.valor(), texto.evidencia(v.inicio(), v.fim(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<Integer> cargaHoraria(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        for (Pattern padrao : List.of(CARGA_ROTULADA, CARGA_SEMANAL)) {
            for (CargoDetectado.Bloco b : blocos) {
                Matcher m = padrao.matcher(texto.normalizado()).region(b.inicioConteudo(), b.fim());
                while (m.find()) {
                    int horas = Integer.parseInt(m.group(1));
                    if (horas >= 10 && horas <= 60) {
                        return Campo.encontrado(horas, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
                    }
                }
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> cnh(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = CNH.matcher(texto.normalizado()).region(b.inicioConteudo(), b.fim());
            if (m.find()) {
                String categoria = (m.group(1) != null ? m.group(1) : m.group(2)).replaceAll("\\s", "").toUpperCase();
                return Campo.encontrado("Categoria " + categoria, texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }

    private static Campo<String> experiencia(TextoDocumento texto, List<CargoDetectado.Bloco> blocos) {
        for (CargoDetectado.Bloco b : blocos) {
            Matcher m = EXPERIENCIA.matcher(texto.normalizado()).region(b.inicioConteudo(), b.fim());
            if (m.find()) {
                return Campo.encontrado(TextoDocumento.compactar(texto.original().substring(m.start(), m.end())),
                        texto.evidencia(m.start(), m.end(), MetodoExtracao.REGEX));
            }
        }
        return Campo.naoEncontrado();
    }
}
