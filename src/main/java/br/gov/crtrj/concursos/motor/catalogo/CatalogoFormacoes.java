package br.gov.crtrj.concursos.motor.catalogo;

import br.gov.crtrj.concursos.motor.texto.Normalizador;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formações técnicas conhecidas e seus sinônimos (resources/catalogo/formacoes.json). */
public final class CatalogoFormacoes {

    private static final String RECURSO_PADRAO = "/catalogo/formacoes.json";

    private final List<EntradaFormacao> entradas;
    private final List<Sinonimo> sinonimos = new ArrayList<>();

    private record Sinonimo(EntradaFormacao entrada, Pattern padrao) {
    }

    /** Uma formação citada no texto: [inicio, fim) no texto normalizado. */
    public record Ocorrencia(EntradaFormacao entrada, int inicio, int fim) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Arquivo(List<EntradaFormacao> formacoes) {
    }

    public CatalogoFormacoes(List<EntradaFormacao> entradas) {
        this.entradas = List.copyOf(entradas);
        for (EntradaFormacao e : entradas) {
            for (String s : e.sinonimos()) {
                String termo = Normalizador.compacto(s);
                String regex = "(?<![a-z0-9])" + Pattern.quote(termo).replace(" ", "\\E\\s+\\Q") + "(?![a-z0-9])";
                sinonimos.add(new Sinonimo(e, Pattern.compile(regex)));
            }
        }
    }

    public static CatalogoFormacoes carregarPadrao() {
        try (InputStream in = CatalogoFormacoes.class.getResourceAsStream(RECURSO_PADRAO)) {
            if (in == null) {
                throw new IllegalStateException("Catálogo não encontrado: " + RECURSO_PADRAO);
            }
            return new CatalogoFormacoes(new ObjectMapper().readValue(in, Arquivo.class).formacoes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<EntradaFormacao> entradas() {
        return entradas;
    }

    /**
     * Formações citadas em [de, ate) do texto normalizado. Quando dois sinônimos se sobrepõem,
     * vale o mais longo ("manutenção automotiva" vence "manutenção").
     */
    public List<Ocorrencia> encontrar(String normalizado, int de, int ate) {
        List<Ocorrencia> todas = new ArrayList<>();
        for (Sinonimo s : sinonimos) {
            Matcher m = s.padrao().matcher(normalizado).region(de, ate);
            while (m.find()) {
                todas.add(new Ocorrencia(s.entrada(), m.start(), m.end()));
            }
        }
        todas.sort(Comparator.comparingInt((Ocorrencia o) -> o.fim() - o.inicio()).reversed());
        List<Ocorrencia> aceitas = new ArrayList<>();
        for (Ocorrencia o : todas) {
            boolean sobreposta = aceitas.stream().anyMatch(a -> o.inicio() < a.fim() && a.inicio() < o.fim());
            if (!sobreposta) {
                aceitas.add(o);
            }
        }
        aceitas.sort(Comparator.comparingInt(Ocorrencia::inicio));
        return aceitas;
    }

    /** Resolve um texto livre ("eletrotécnica", "Técnico em Eletrotécnica", "eletrotecnica") para uma entrada. */
    public Optional<EntradaFormacao> resolver(String texto) {
        String chave = Normalizador.chave(texto).replaceFirst("^(curso )?tecnico (em |de )?", "");
        for (EntradaFormacao e : entradas) {
            if (e.id().equals(chave.replace(' ', '_')) || (" " + Normalizador.chave(e.nome())).endsWith(" " + chave)) {
                return Optional.of(e);
            }
        }
        List<Ocorrencia> ocorrencias = encontrar(chave, 0, chave.length());
        return ocorrencias.isEmpty() ? Optional.empty() : Optional.of(ocorrencias.get(0).entrada());
    }
}
