package br.gov.crtrj.concursos.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChaveApiTest {

    @TempDir
    Path pasta;

    private ConcursosProperties.Api api(String chave, Path arquivo) {
        return new ConcursosProperties.Api(chave, arquivo.toString(), List.of());
    }

    @Test
    void chaveDefinidaTemPrioridadeENaoCriaArquivo() {
        Path arquivo = pasta.resolve("chave-api.txt");
        String chave = "uma-chave-definida-com-mais-de-24-caracteres";

        assertThat(SegurancaConfig.chaveDaApi(api("  " + chave + "  ", arquivo))).isEqualTo(chave);
        assertThat(arquivo).doesNotExist();
    }

    @Test
    void chaveCurtaNaoSobe() {
        assertThatThrownBy(() -> SegurancaConfig.chaveDaApi(api("curta", pasta.resolve("x.txt"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONCURSOS_API_CHAVE")
                .hasMessageContaining("24");
    }

    @Test
    void semChaveUmaEGeradaUmaVezEReaproveitada() throws Exception {
        Path arquivo = pasta.resolve("dados").resolve("chave-api.txt");

        String primeira = SegurancaConfig.chaveDaApi(api("", arquivo));

        assertThat(primeira).hasSizeGreaterThanOrEqualTo(SegurancaConfig.TAMANHO_MINIMO_CHAVE);
        assertThat(Files.readString(arquivo).strip()).isEqualTo(primeira);
        assertThat(SegurancaConfig.chaveDaApi(api(null, arquivo))).isEqualTo(primeira);
    }

    @Test
    void arquivoComChaveCurtaNaoSobe() throws Exception {
        Path arquivo = pasta.resolve("chave-api.txt");
        Files.writeString(arquivo, "123456\n");

        assertThatThrownBy(() -> SegurancaConfig.chaveDaApi(api("", arquivo)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chave-api.txt");
    }
}
