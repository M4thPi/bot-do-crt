package br.gov.crtrj.concursos.coleta.dou;

import br.gov.crtrj.concursos.coleta.PistaEncontrada;
import br.gov.crtrj.concursos.coleta.RegiaoRj;
import br.gov.crtrj.concursos.persistencia.EdicaoDiarioRepositorio;
import br.gov.crtrj.concursos.persistencia.EdicaoDiarioRepositorio.Situacao;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FonteDouTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 1);

    /** Um ato no formato do XML do INLABS (o texto vem em HTML dentro de CDATA). */
    private static String ato(String id, String categoria, String identifica, String textoHtml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <xml><article id="%s" name="Edital" idOficio="1" pubName="DO3" artType="Edital" pubDate="01/10/2026"
                 artClass="00001" artCategory="%s" artSize="12" artNotes="" numberPage="45"
                 pdfPage="http://pesquisa.in.gov.br/imprensa/jsp/visualiza/index.jsp?data=01/10/2026&amp;jornal=530&amp;pagina=45"
                 editionNumber="187" idMateria="%s">
                <body><Identifica><![CDATA[%s]]></Identifica><Data><![CDATA[]]></Data><Ementa><![CDATA[]]></Ementa>
                <Titulo><![CDATA[]]></Titulo><SubTitulo><![CDATA[]]></SubTitulo><Texto><![CDATA[%s]]></Texto></body>
                <Midias/></article></xml>
                """.formatted(id, categoria, id, identifica, textoHtml);
    }

    private static byte[] pacote(Map<String, String> arquivos) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (Map.Entry<String, String> a : arquivos.entrySet()) {
                zip.putNextEntry(new ZipEntry(a.getKey()));
                zip.write(a.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return saida.toByteArray();
    }

    private static final String UFF = ato("41000001", "Ministério da Educação/Universidade Federal Fluminense/Pró-Reitoria de Gestão de Pessoas",
            "EDITAL Nº 96, DE 30 DE SETEMBRO DE 2026",
            "<p>O PRÓ-REITOR DE GESTÃO DE PESSOAS DA UNIVERSIDADE FEDERAL FLUMINENSE torna público que estarão abertas as "
                    + "inscrições para o Concurso Público destinado ao provimento de cargos técnico-administrativos em educação.</p>"
                    + "<p>1.1 Cargo: Técnico em Eletrotécnica. Requisito: curso técnico em Eletrotécnica e registro no CFT.</p>"
                    + "<p>2.1 As inscrições serão feitas exclusivamente no endereço www.coseac.uff.br/concursos.</p>");
    private static final String OUTRO_ESTADO = ato("41000002", "Ministério da Educação/Universidade Federal do Paraná",
            "EDITAL Nº 12, DE 30 DE SETEMBRO DE 2026",
            "<p>A UFPR torna pública a abertura das inscrições para o Concurso Público de Técnico de Laboratório, "
                    + "com lotação em Curitiba.</p>");
    private static final String HOMOLOGACAO = ato("41000003", "Ministério da Educação/Universidade Federal Fluminense",
            "EDITAL Nº 97, DE 30 DE SETEMBRO DE 2026",
            "<p>Homologa o resultado final do Concurso Público para Técnico de Laboratório aberto pelo Edital nº 40/2026.</p>");
    private static final String NACIONAL_COM_VAGA_NO_RJ = ato("41000004", "Ministério da Ciência, Tecnologia e Inovação/Comissão Nacional de Energia Nuclear",
            "EDITAL Nº 1, DE 30 DE SETEMBRO DE 2026",
            "<p>Torna pública a realização de concurso público para o cargo de Técnico em Mecânica, com vagas para "
                    + "o Instituto de Engenharia Nuclear, no Rio de Janeiro/RJ. Inscrições em www.cnen.gov.br/concurso.</p>");
    private static final String PROFESSOR_EBTT = ato("41000005", "Ministério da Educação/Instituto Federal do Rio de Janeiro",
            "EDITAL Nº 30, DE 30 DE SETEMBRO DE 2026",
            "<p>Estão abertas as inscrições para o concurso público de Professor do Ensino Básico, Técnico e Tecnológico.</p>");
    private static final String LICITACAO = ato("41000006", "Ministério da Educação/Universidade Federal Fluminense",
            "AVISO DE LICITAÇÃO", "<p>Pregão eletrônico para manutenção predial.</p>");

    @Test
    void ficaSoComEditalDeAberturaComCargoTecnicoNoRj() throws IOException {
        byte[] zip = pacote(Map.of("a1.xml", UFF, "a2.xml", OUTRO_ESTADO, "a3.xml", HOMOLOGACAO,
                "a4.xml", NACIONAL_COM_VAGA_NO_RJ, "a5.xml", PROFESSOR_EBTT, "a6.xml", LICITACAO));
        EdicaoDiarioRepositorio edicoes = mock(EdicaoDiarioRepositorio.class);
        FonteDou.Inlabs inlabs = (data, secao, atos) -> data.equals(HOJE)
                ? LeitorAtosDou.ler(new ByteArrayInputStream(zip), data, secao, atos) : -1;
        FonteDou fonte = new FonteDou(inlabs, List.of("DO3"), 1, new RegiaoRj(List.of("CNEN")), edicoes, RELOGIO);

        List<PistaEncontrada> pistas = fonte.listar(url -> false, new ArrayList<>());

        assertThat(pistas).extracting(PistaEncontrada::orgao)
                .containsExactlyInAnyOrder("Universidade Federal Fluminense", "Comissão Nacional de Energia Nuclear");
        PistaEncontrada uff = pistas.stream().filter(p -> p.orgao().contains("Fluminense")).findFirst().orElseThrow();
        assertThat(uff.fonte()).isEqualTo("DOU");
        assertThat(uff.titulo()).isEqualTo("EDITAL Nº 96, DE 30 DE SETEMBRO DE 2026 — Universidade Federal Fluminense");
        assertThat(uff.url()).startsWith("http://pesquisa.in.gov.br/imprensa/jsp/visualiza/index.jsp?data=01/10/2026&jornal=530")
                .endsWith("#materia-41000001");
        assertThat(uff.texto()).contains("www.coseac.uff.br/concursos").doesNotContain("<p>");
        assertThat(uff.motivoDescarte()).isNull();
        // ontem (sem edição) fica registrado; hoje, lido com 6 atos e 2 pistas
        verify(edicoes).registrar(eq("DOU"), eq(HOJE.minusDays(1)), eq("DO3"), eq(Situacao.SEM_EDICAO), eq(0), eq(0), any());
        verify(edicoes).registrar(eq("DOU"), eq(HOJE), eq("DO3"), eq(Situacao.LIDA), eq(6), eq(2), any());
    }

    @Test
    void edicaoJaLidaNaoEBaixadaDeNovoEEdicaoDeHojeAindaNaoPublicadaFicaParaDepois() throws IOException {
        EdicaoDiarioRepositorio edicoes = mock(EdicaoDiarioRepositorio.class);
        when(edicoes.lida("DOU", HOJE.minusDays(1), "DO3")).thenReturn(true);
        List<LocalDate> pedidas = new ArrayList<>();
        FonteDou.Inlabs inlabs = (data, secao, atos) -> {
            pedidas.add(data);
            return -1;
        };

        new FonteDou(inlabs, List.of("DO3"), 1, new RegiaoRj(List.of()), edicoes, RELOGIO).listar(url -> false, new ArrayList<>());

        assertThat(pedidas).containsExactly(HOJE);
        verify(edicoes, never()).registrar(any(), eq(HOJE), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void semCadastroNoInlabsAFonteFicaParada() throws IOException {
        EdicaoDiarioRepositorio edicoes = mock(EdicaoDiarioRepositorio.class);
        FonteDou fonte = new FonteDou(null, List.of("DO3"), 3, new RegiaoRj(List.of()), edicoes, RELOGIO);

        assertThat(fonte.configurada()).isFalse();
        assertThat(fonte.listar(url -> false, new ArrayList<>())).isEmpty();
        verify(edicoes, never()).lida(any(), any(), any());
    }
}
