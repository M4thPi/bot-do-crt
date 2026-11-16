package br.gov.crtrj.concursos.documentos;

import br.gov.crtrj.concursos.config.ConcursosProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Guarda uma cópia de cada documento analisado, nomeada pelo sha256 — nunca sobrescreve. */
@Component
public class ArmazenamentoDocumentos {

    private final Path raiz;

    public ArmazenamentoDocumentos(ConcursosProperties props) {
        this.raiz = Path.of(props.armazenamento()).toAbsolutePath().normalize();
    }

    public Path salvar(String sha256, byte[] conteudo) {
        try {
            Files.createDirectories(raiz);
            Path destino = raiz.resolve(sha256 + ".pdf");
            if (!Files.exists(destino)) {
                Files.write(destino, conteudo);
            }
            return destino;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao guardar documento " + sha256, e);
        }
    }

    public byte[] ler(String caminho) {
        try {
            return Files.readAllBytes(Path.of(caminho));
        } catch (IOException e) {
            throw new UncheckedIOException("Documento não encontrado no armazenamento: " + caminho, e);
        }
    }
}
