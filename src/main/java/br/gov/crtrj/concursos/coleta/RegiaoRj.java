package br.gov.crtrj.concursos.coleta;

import br.gov.crtrj.concursos.motor.texto.Normalizador;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O texto cita o estado do RJ? Usado para filtrar o que vem das bancas e do Diário Oficial da
 * União, que publicam concursos do país inteiro.
 *
 * <p>Vale "RJ", "Rio de Janeiro", "fluminense", "carioca", sigla terminada em "RJ" (CBMERJ), um dos
 * órgãos configurados (com sede ou vagas no RJ) e, em textos curtos como o título do concurso, o nome de um município do estado.
 * Municípios com nome repetido em outro estado ou que também são sobrenome (Carmo, Mendes,
 * Valença, Resende, Rio Claro...) só contam junto com "RJ" — por isso ficam fora da lista.
 */
public final class RegiaoRj {

    static final List<String> TERMOS_DO_ESTADO = List.of("rj", "rio de janeiro", "fluminense", "carioca");

    /** Municípios do RJ cujo nome não se confunde com outro lugar nem com sobrenome. */
    static final List<String> MUNICIPIOS = List.of(
            "angra dos reis", "aperibe", "araruama", "armacao dos buzios", "buzios", "arraial do cabo",
            "barra do pirai", "barra mansa", "belford roxo", "bom jesus do itabapoana", "cabo frio",
            "cachoeiras de macacu", "campos dos goytacazes", "carapebus", "cardoso moreira", "casimiro de abreu",
            "comendador levy gasparian", "conceicao de macabu", "duque de caxias",
            "engenheiro paulo de frontin", "guapimirim", "iguaba grande", "itaborai", "itaguai", "italva",
            "itaocara", "itaperuna", "itatiaia", "japeri", "laje do muriae", "macae", "mage", "mangaratiba",
            "marica", "miguel pereira", "nilopolis", "niteroi", "nova friburgo", "nova iguacu", "paracambi",
            "paraiba do sul", "paraty", "paty do alferes", "petropolis", "pinheiral", "porciuncula", "quatis",
            "queimados", "quissama", "rio das flores", "rio das ostras", "santa maria madalena",
            "santo antonio de padua", "sao fidelis", "sao francisco de itabapoana", "sao goncalo",
            "sao joao da barra", "sao joao de meriti", "sao jose de uba", "sao jose do vale do rio preto",
            "sao pedro da aldeia", "sao sebastiao do alto", "saquarema", "seropedica", "silva jardim",
            "tangua", "teresopolis", "trajano de moraes", "tres rios", "varre sai", "vassouras",
            "volta redonda");

    /** "São Gonçalo do Amarante", "Teresópolis de Goiás": o nome do RJ seguido de "do/de..." é outro lugar. */
    private static final Set<String> CONTINUA_OUTRO_LUGAR = Set.of("do", "dos", "da", "das", "de");
    /** Sigla de órgão terminada em "RJ" ("CBMERJ", "PMERJ", "CREMERJ", "TJRJ"): nenhuma palavra em português termina assim. */
    private static final Pattern SIGLA_DO_ESTADO = Pattern.compile(" ([a-z]{2,}rj) ");
    /** Cidades de outros estados que contêm o nome de um município do RJ. */
    private static final List<String> OUTROS_ESTADOS = List.of("nova petropolis");

    private final List<String> doEstado;
    private final List<String> municipios;

    /** @param orgaos órgãos com sede ou vagas no RJ ("UFRJ", "Petrobras"...), da configuração */
    public RegiaoRj(List<String> orgaos) {
        List<String> termos = new ArrayList<>(TERMOS_DO_ESTADO);
        orgaos.stream().map(Normalizador::chave).filter(t -> !t.isBlank()).forEach(termos::add);
        this.doEstado = List.copyOf(termos);
        this.municipios = MUNICIPIOS;
    }

    /** Título ou cartão de concurso: vale também o nome de um município. Devolve o termo achado ou null. */
    public String termoCitado(String texto) {
        String chave = chave(texto);
        if (chave == null) {
            return null;
        }
        String termo = noEstado(chave);
        return termo != null ? termo : procurar(chave, municipios, true);
    }

    public boolean cita(String texto) {
        return termoCitado(texto) != null;
    }

    /**
     * Só "RJ", "Rio de Janeiro"... e os órgãos configurados — para textos longos (ato do diário
     * oficial), onde nome de município aparece como nome de pessoa ou de rua ("Av. Duque de Caxias").
     */
    public String termoDoEstadoCitado(String texto) {
        String chave = chave(texto);
        return chave == null ? null : noEstado(chave);
    }

    private String noEstado(String chave) {
        String termo = procurar(chave, doEstado, false);
        if (termo != null) {
            return termo;
        }
        Matcher sigla = SIGLA_DO_ESTADO.matcher(chave);
        return sigla.find() ? sigla.group(1) : null;
    }

    private static String chave(String texto) {
        if (texto == null) {
            return null;
        }
        // "CEFET/RJ" → "cefet rj": a comparação é por palavra inteira
        String chave = " " + Normalizador.chave(texto) + " ";
        for (String outro : OUTROS_ESTADOS) {
            chave = chave.replace(" " + outro + " ", " ");
        }
        return chave;
    }

    private static String procurar(String chave, List<String> termos, boolean municipio) {
        for (String t : termos) {
            int de = 0;
            int i;
            while ((i = chave.indexOf(" " + t + " ", de)) >= 0) {
                int fim = i + t.length() + 2;
                String seguinte = chave.substring(fim).split(" ", 2)[0];
                if (!municipio || !CONTINUA_OUTRO_LUGAR.contains(seguinte)) {
                    return t;
                }
                de = i + 1;
            }
        }
        return null;
    }
}
