package br.gov.crtrj.concursos.cli;

import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.motor.AnalisadorEdital;
import br.gov.crtrj.concursos.motor.CargoAnalisado;
import br.gov.crtrj.concursos.motor.OrigemDocumento;
import br.gov.crtrj.concursos.motor.ResultadoAnalise;
import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;
import br.gov.crtrj.concursos.motor.texto.LimpadorTexto;
import br.gov.crtrj.concursos.relatorio.RenderizadorMarkdown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Analisa um edital sem subir a API:
 * <pre>
 *   java -jar concursos-tecnicos.jar analisar edital.pdf [--json] [--oficial] [--saida relatorio.md]
 *   java -jar concursos-tecnicos.jar analisar https://.../edital.pdf
 * </pre>
 */
public final class AnalisarEditalCli {

    private AnalisarEditalCli() {
    }

    public static int executar(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String entrada = null;
        boolean json = false;
        boolean oficial = false;
        boolean texto = false;
        Path saida = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json" -> json = true;
                case "--texto" -> texto = true;
                case "--oficial" -> oficial = true;
                case "--saida" -> saida = i + 1 < args.length ? Path.of(args[++i]) : null;
                default -> entrada = args[i];
            }
        }
        if (entrada == null) {
            System.err.println("Uso: analisar <arquivo.pdf | URL> [--json] [--oficial] [--texto] [--saida arquivo]");
            System.err.println("  --oficial  confirma manualmente que o documento veio de fonte oficial");
            System.err.println("  --texto    mostra o texto extraído, página a página, como o analisador vê");
            return 2;
        }
        try {
            String relatorio = texto ? textoExtraido(ler(entrada).conteudo())
                    : json ? jackson().writeValueAsString(analisar(entrada, oficial)) : markdown(analisar(entrada, oficial));
            if (saida != null) {
                Files.writeString(saida, relatorio, StandardCharsets.UTF_8);
                out.println("Relatório gravado em " + saida.toAbsolutePath());
            } else {
                out.println(relatorio);
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Erro: " + e.getMessage());
            return 1;
        }
    }

    /** Conteúdo do PDF e de onde veio (url = null para arquivo local). */
    private record Entrada(byte[] conteudo, String nome, String url) {
    }

    private static Entrada ler(String entrada) throws IOException {
        if (entrada.startsWith("http://") || entrada.startsWith("https://")) {
            BaixadorDocumentos.Download download = BaixadorDocumentos.padrao().baixar(entrada);
            return new Entrada(download.conteudo(), download.urlFinal(), download.urlFinal());
        }
        Path arquivo = Path.of(entrada);
        return new Entrada(Files.readAllBytes(arquivo), arquivo.getFileName().toString(), null);
    }

    private static ResultadoAnalise analisar(String entradaTexto, boolean oficial) throws IOException {
        Entrada entrada = ler(entradaTexto);
        ValidadorFontes.TipoFonte tipo = oficial ? ValidadorFontes.TipoFonte.CONFIRMADA_MANUALMENTE
                : ValidadorFontes.padrao().classificar(entrada.url());
        AnalisadorEdital analisador = new AnalisadorEdital(CatalogoFormacoes.carregarPadrao());
        return analisador.analisarPdf(entrada.conteudo(),
                new OrigemDocumento(entrada.nome(), entrada.url(), tipo.oficial(), tipo.name()));
    }

    private static String textoExtraido(byte[] pdf) throws IOException {
        List<String> paginas = LimpadorTexto.limpar(new LeitorPdf().ler(pdf).paginasBrutas());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paginas.size(); i++) {
            sb.append("===== página ").append(i + 1).append(" =====\n").append(paginas.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String markdown(ResultadoAnalise r) {
        StatusConcurso status = StatusConcurso.porDatas(null,
                r.concurso().inscricaoInicio().valorOu(null), r.concurso().inscricaoFim().valorOu(null),
                r.concurso().dataProva().valorOu(null), LocalDate.now());
        List<RenderizadorMarkdown.Item> itens = new ArrayList<>();
        for (CargoAnalisado c : r.cargos()) {
            itens.add(new RenderizadorMarkdown.Item(r, c, status, null));
        }
        StringBuilder sb = new StringBuilder(new RenderizadorMarkdown().renderizar(itens));
        sb.append("\n_Cargos sem sinal de nível técnico ignorados: ").append(r.cargosDescartados()).append("._\n");
        for (String aviso : r.avisos()) {
            sb.append("\n> ⚠ ").append(aviso).append('\n');
        }
        return sb.toString();
    }

    private static ObjectMapper jackson() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
