package br.gov.crtrj.concursos.coleta;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/** Onde o robô procura concursos novos: agregador, sites das bancas, diário oficial. */
public interface FonteDescoberta {

    String nome();

    /**
     * @param conhecida true para endereços que já são pista (a fonte pode pular trabalho extra com eles)
     * @param avisos    problemas que não impedem a leitura do resto (uma banca fora do ar, por exemplo)
     */
    List<PistaEncontrada> listar(Predicate<String> conhecida, List<String> avisos) throws IOException;
}
