package br.gov.crtrj.concursos.motor.leitor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Extrai o texto de cada página do PDF, preservando a numeração das páginas. */
public final class LeitorPdf {

    /** Abaixo disso a página é tratada como sem camada de texto (provável digitalização). */
    private static final int MIN_CARACTERES = 40;

    public DocumentoLido ler(byte[] pdf) throws IOException {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            if (documento.isEncrypted()) {
                documento.setAllSecurityToBeRemoved(true);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            int total = documento.getNumberOfPages();
            List<String> paginas = new ArrayList<>(total);
            List<Integer> semTexto = new ArrayList<>();
            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String texto = stripper.getText(documento);
                paginas.add(texto);
                if (texto.replaceAll("\\s+", "").length() < MIN_CARACTERES) {
                    semTexto.add(p);
                }
            }
            return new DocumentoLido(paginas, semTexto);
        }
    }

    public static boolean pareceSerPdf(byte[] conteudo) {
        return conteudo != null && conteudo.length > 4
                && conteudo[0] == '%' && conteudo[1] == 'P' && conteudo[2] == 'D' && conteudo[3] == 'F';
    }
}
