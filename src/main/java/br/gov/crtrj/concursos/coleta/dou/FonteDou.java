package br.gov.crtrj.concursos.coleta.dou;

import br.gov.crtrj.concursos.coleta.FonteDescoberta;
import br.gov.crtrj.concursos.coleta.PistaEncontrada;
import br.gov.crtrj.concursos.coleta.RegiaoRj;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.persistencia.EdicaoDiarioRepositorio;
import br.gov.crtrj.concursos.persistencia.EdicaoDiarioRepositorio.Situacao;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descoberta no Diário Oficial da União, pelo INLABS (serviço da Imprensa Nacional para acesso
 * automatizado, com cadastro). Lê cada edição uma vez e fica com os editais de abertura de
 * concurso público com cargo técnico de órgão do RJ — ou de órgão nacional com vaga no RJ.
 *
 * <p>O ato vira pista com o texto guardado: o edital em PDF é procurado nos sites que ele cita.
 */
public final class FonteDou implements FonteDescoberta {

    public static final String NOME = "DOU";

    /** Acesso às edições. */
    public interface Inlabs {
        /**
         * @return quantos atos a edição tem, ou -1 se ela não existe (ainda não publicada, fim de semana)
         */
        int lerEdicao(LocalDate data, String secao, Consumer<AtoDou> atos) throws IOException;
    }

    private static final Pattern ABERTURA = Pattern.compile(
            "(estarao|serao|estao|ficam) abertas as inscricoes|abertura das? inscricoes|abertura de inscricoes"
                    + "|torna(m)? public[oa] a (abertura|realizacao)|inscricoes (estarao|serao) abertas");
    /** Professor do ensino básico, técnico e tecnológico e "técnico-administrativo" não são, sozinhos, cargo técnico. */
    private static final Pattern TECNICO_QUE_NAO_E_CARGO = Pattern.compile(
            "ensino basico,? tecnico e tecnologico|tecnicos?[- ]administrativ\\w*");
    /** "lotação no Rio de Janeiro", "vagas para Niterói": cargo de órgão nacional com vaga no RJ. */
    private static final Pattern LOCAL_DA_VAGA = Pattern.compile(
            "lotac\\w*|lotad[oa]s?|local de (trabalho|exercicio)|vagas? (para|em|no|na)|cidade d[eo]|municipio d[eo]|unidade d[eo]|campus");
    private static final int ALCANCE_LOCAL = 120;
    private static final int MAX_TEXTO = 30_000;

    private final Inlabs inlabs;
    private final List<String> secoes;
    private final int diasParaTras;
    private final RegiaoRj regiao;
    private final EdicaoDiarioRepositorio edicoes;
    private final Clock relogio;

    /** @param inlabs null quando o cadastro no INLABS ainda não foi configurado (a fonte fica parada) */
    public FonteDou(Inlabs inlabs, List<String> secoes, int diasParaTras, RegiaoRj regiao,
                    EdicaoDiarioRepositorio edicoes, Clock relogio) {
        this.inlabs = inlabs;
        this.secoes = List.copyOf(secoes);
        this.diasParaTras = Math.max(0, diasParaTras);
        this.regiao = regiao;
        this.edicoes = edicoes;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return NOME;
    }

    public boolean configurada() {
        return inlabs != null;
    }

    @Override
    public List<PistaEncontrada> listar(Predicate<String> conhecida, List<String> avisos) throws IOException {
        if (inlabs == null) {
            return List.of();
        }
        LocalDate hoje = LocalDate.now(relogio);
        List<PistaEncontrada> pistas = new ArrayList<>();
        for (LocalDate dia = hoje.minusDays(diasParaTras); !dia.isAfter(hoje); dia = dia.plusDays(1)) {
            for (String secao : secoes) {
                if (edicoes.lida(NOME, dia, secao)) {
                    continue;
                }
                List<PistaEncontrada> daEdicao = new ArrayList<>();
                int atos = inlabs.lerEdicao(dia, secao, ato -> avaliar(ato).ifPresent(daEdicao::add));
                if (atos < 0) {
                    // edição de hoje ainda não saiu: tenta de novo na próxima rodada
                    if (dia.isBefore(hoje)) {
                        edicoes.registrar(NOME, dia, secao, Situacao.SEM_EDICAO, 0, 0, LocalDateTime.now(relogio));
                    }
                    continue;
                }
                pistas.addAll(daEdicao);
                edicoes.registrar(NOME, dia, secao, Situacao.LIDA, atos, daEdicao.size(), LocalDateTime.now(relogio));
            }
        }
        return pistas;
    }

    /** O ato é edital de abertura de concurso com cargo técnico no RJ? */
    Optional<PistaEncontrada> avaliar(AtoDou ato) {
        String tudo = Normalizador.compacto(ato.titulo() + " " + ato.ementa() + " " + ato.texto());
        if (!tudo.contains("concurso publico") || !ABERTURA.matcher(tudo).find()
                || !TECNICO_QUE_NAO_E_CARGO.matcher(tudo).replaceAll(" ").contains("tecnic")) {
            return Optional.empty();
        }
        String termo = regiao.termoDoEstadoCitado(ato.orgao());
        if (termo == null) {
            termo = vagaNoRj(tudo);
        }
        if (termo == null) {
            return Optional.empty();
        }
        String orgao = orgaoCurto(ato.orgao());
        String texto = ato.texto().length() > MAX_TEXTO ? ato.texto().substring(0, MAX_TEXTO) : ato.texto();
        return Optional.of(new PistaEncontrada(NOME, ato.link(), ato.titulo() + " — " + orgao, orgao, "RJ",
                null, null, null, null, texto, null));
    }

    private String vagaNoRj(String texto) {
        Matcher m = LOCAL_DA_VAGA.matcher(texto);
        while (m.find()) {
            String adiante = texto.substring(m.end(), Math.min(texto.length(), m.end() + ALCANCE_LOCAL));
            String termo = regiao.termoCitado(adiante);
            if (termo != null) {
                return termo;
            }
        }
        return null;
    }

    /** "Ministério da Educação/Universidade Federal Fluminense/Pró-Reitoria" → "Universidade Federal Fluminense". */
    static String orgaoCurto(String hierarquia) {
        String[] partes = hierarquia.split("/");
        String escolhido = partes.length > 1 ? partes[1] : partes[0];
        return escolhido.isBlank() ? hierarquia : escolhido.strip();
    }
}
