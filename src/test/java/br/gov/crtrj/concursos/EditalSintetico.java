package br.gov.crtrj.concursos;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** Gera PDFs de edital para teste — cada página é uma lista de linhas. */
public final class EditalSintetico {

    private EditalSintetico() {
    }

    public static byte[] pdf(List<List<String>> paginas) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            PDType1Font fonte = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> linhas : paginas) {
                PDPage pagina = new PDPage(PDRectangle.A4);
                doc.addPage(pagina);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                    cs.beginText();
                    cs.setFont(fonte, 8);
                    cs.newLineAtOffset(30, 810);
                    for (String linha : linhas) {
                        cs.showText(linha);
                        cs.newLineAtOffset(0, -12);
                    }
                    cs.endText();
                }
            }
            doc.save(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edital municipal em texto corrido, com casos de referência da especificação. */
    public static byte[] prefeitura() {
        return pdf(paginasPrefeitura());
    }

    /** O mesmo edital com linhas trocadas (nova versão, retificação consolidada...). */
    public static byte[] prefeitura(UnaryOperator<String> ajuste) {
        return pdf(paginasPrefeitura().stream().map(p -> p.stream().map(ajuste).toList()).toList());
    }

    private static List<List<String>> paginasPrefeitura() {
        return List.of(
                List.of(
                        "PREFEITURA MUNICIPAL DE NITERÓI",
                        "ESTADO DO RIO DE JANEIRO",
                        "EDITAL DE CONCURSO PÚBLICO Nº 01/2026",
                        "A Prefeitura Municipal de Niterói torna pública a realização de concurso público, organizado e executado",
                        "pela Fundação Getulio Vargas (FGV).",
                        "",
                        "1. DAS DISPOSIÇÕES PRELIMINARES",
                        "1.1 O concurso destina-se ao provimento de cargos de nível médio e técnico.",
                        "",
                        "2. DOS CARGOS",
                        "2.1 CARGO: TÉCNICO DE MANUTENÇÃO – ELETROTÉCNICA",
                        "REQUISITOS: Certificado de conclusão de Curso Técnico em Eletrotécnica, reconhecido pelo MEC, e registro",
                        "no respectivo Conselho Regional dos Técnicos Industriais.",
                        "VAGAS: 8, sendo ampla concorrência: 7 e PcD: 1; cadastro reserva.",
                        "VENCIMENTO BÁSICO: R$ 4.250,00",
                        "REMUNERAÇÃO INICIAL: R$ 5.180,00",
                        "CARGA HORÁRIA: 40 horas semanais",
                        "2.2 CARGO: TÉCNICO ADMINISTRATIVO",
                        "REQUISITOS: Ensino médio completo.",
                        "VAGAS: 10",
                        "VENCIMENTO BÁSICO: R$ 2.500,00",
                        "2.3 CARGO: TÉCNICO EM ENFERMAGEM",
                        "REQUISITOS: Curso Técnico em Enfermagem e registro no COREN.",
                        "VAGAS: 4",
                        "2.4 CARGO: TÉCNICO DE OPERAÇÃO",
                        "REQUISITOS: Nível médio técnico em qualquer área.",
                        "VAGAS: 3",
                        "2.5 CARGO: TÉCNICO EM QUÍMICA",
                        "REQUISITOS: Curso Técnico em Química e registro no CRQ.",
                        "VAGAS: 2",
                        "2.6 CARGO: PROFESSOR DE MATEMÁTICA",
                        "REQUISITOS: Licenciatura plena em Matemática.",
                        "VAGAS: 12",
                        "",
                        "3. DAS INSCRIÇÕES",
                        "3.1 As inscrições serão realizadas no período de 10/09/2026 a 15/10/2026, exclusivamente pelo site",
                        "https://conhecimento.fgv.br/concursos/niteroi26.",
                        "3.2 A taxa de inscrição será de R$ 75,00 para os cargos de nível médio e técnico.",
                        "3.3 O pedido de isenção da taxa de inscrição poderá ser feito de 10/09/2026 a 15/09/2026.",
                        "",
                        "4. DAS PROVAS",
                        "4.1 As provas objetivas serão realizadas em 29/11/2026.",
                        "4.2 Haverá prova prática para o cargo de manutenção."),
                List.of(
                        "5. DOS REQUISITOS PARA INVESTIDURA",
                        "5.1 São requisitos para a posse: ter nacionalidade brasileira e apresentar registro no órgão de classe",
                        "competente, quando exigido para o cargo.",
                        "",
                        "ANEXO I – CONTEÚDO PROGRAMÁTICO",
                        "TÉCNICO EM QUÍMICA",
                        "Química geral e inorgânica.",
                        "TÉCNICO DE MANUTENÇÃO – ELETROTÉCNICA",
                        "Eletrotécnica: circuitos elétricos, NR-10, instalações elétricas."));
    }

    /** Anexo em formato de tabela: nome do cargo e requisito na mesma linha. */
    public static byte[] tabela() {
        return tabela(UnaryOperator.identity());
    }

    public static byte[] tabela(UnaryOperator<String> ajuste) {
        return pdf(List.of(Stream.of(
                "COMPANHIA ESTADUAL DE ÁGUAS E ESGOTOS DO RIO DE JANEIRO – CEDAE",
                "EDITAL Nº 02/2026",
                "ANEXO I – QUADRO DE VAGAS E REQUISITOS",
                "Código Cargo Requisitos Vagas Remuneração",
                "201 Técnico de Operação – Eletromecânica Curso Técnico em Eletromecânica e registro ativo no CRT-RJ 5 R$ 3.900,00",
                "202 Técnico de Segurança do Trabalho Curso Técnico em Segurança do Trabalho e registro no MTE 2 R$ 3.900,00",
                "203 Técnico em Edificações Ensino médio completo 3 R$ 3.000,00").map(ajuste).toList()));
    }
}
