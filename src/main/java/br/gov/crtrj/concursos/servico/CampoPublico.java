package br.gov.crtrj.concursos.servico;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dados que a página pública mostra além da classificação. O revisor pode ocultar qualquer um
 * quando a extração estiver errada — aí a página diz "Confira no edital.".
 */
public enum CampoPublico {
    FORMACOES("Formação aceita", false),
    VAGAS("Vagas", false),
    SALARIO_BASE("Salário-base", false),
    REMUNERACAO("Remuneração total", false),
    CARGA_HORARIA("Carga horária", false),
    TAXA("Taxa de inscrição", false),
    CONSELHO("Conselho profissional", false),
    INSCRICOES("Inscrições", true),
    PROVA("Data da prova", true),
    BANCA("Banca", true),
    SITE_INSCRICAO("Onde se inscrever", true);

    public static final String OCULTO = "Confira no edital.";

    private final String rotulo;
    private final boolean doConcurso;

    CampoPublico(String rotulo, boolean doConcurso) {
        this.rotulo = rotulo;
        this.doConcurso = doConcurso;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Vale para o concurso inteiro: ocultar em um cargo oculta no cabeçalho do concurso. */
    public boolean doConcurso() {
        return doConcurso;
    }

    /** Lê a coluna gravada ("SALARIO_BASE,TAXA"); valores desconhecidos são ignorados. */
    public static Set<CampoPublico> ler(String gravado) {
        Set<CampoPublico> campos = EnumSet.noneOf(CampoPublico.class);
        if (gravado == null || gravado.isBlank()) {
            return campos;
        }
        for (String s : gravado.split(",")) {
            Arrays.stream(values()).filter(c -> c.name().equals(s.strip())).findFirst().ifPresent(campos::add);
        }
        return campos;
    }

    /** Converte o que veio do formulário/API; nome desconhecido é erro de entrada. */
    public static Set<CampoPublico> deNomes(Collection<String> nomes) {
        Set<CampoPublico> campos = EnumSet.noneOf(CampoPublico.class);
        if (nomes == null) {
            return campos;
        }
        for (String nome : nomes) {
            try {
                campos.add(valueOf(nome.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Campo desconhecido para ocultar: " + nome);
            }
        }
        return campos;
    }

    public static String gravar(Set<CampoPublico> campos) {
        return campos.isEmpty() ? null : campos.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
