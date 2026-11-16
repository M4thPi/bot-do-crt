package br.gov.crtrj.concursos.motor.extracao;

import java.util.List;

/**
 * Um cargo encontrado no edital, com todos os blocos em que aparece (quadro de vagas, requisitos,
 * atribuições...). Blocos em seções excluídas não são usados para requisitos.
 */
public record CargoDetectado(String nome, int inicioNome, int fimNome, String especialidade,
                             int inicioEspecialidade, List<Bloco> blocos) {

    /**
     * @param inicio          início da linha de cabeçalho
     * @param inicioConteudo  fim do nome do cargo (o resto da linha já pode ser requisito, em tabelas)
     * @param fim             fim do bloco
     * @param excluido        bloco em conteúdo programático, atribuições etc.
     */
    public record Bloco(int inicio, int inicioConteudo, int fim, boolean excluido) {
    }

    public List<Bloco> blocosUteis() {
        return blocos.stream().filter(b -> !b.excluido()).toList();
    }
}
