package br.gov.crtrj.concursos.motor.alteracoes;

import br.gov.crtrj.concursos.motor.alteracoes.AnalisadorAlteracoes.Alteracao;
import br.gov.crtrj.concursos.motor.alteracoes.AnalisadorAlteracoes.Resultado;
import br.gov.crtrj.concursos.motor.alteracoes.AnalisadorAlteracoes.Tipo;
import br.gov.crtrj.concursos.motor.leitor.DocumentoLido;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalisadorAlteracoesTest {

    private final AnalisadorAlteracoes analisador = new AnalisadorAlteracoes();

    private Resultado analisar(String... paginas) {
        return analisador.analisar(new DocumentoLido(List.of(paginas), List.of()));
    }

    private static List<Alteracao> doTipo(Resultado r, Tipo tipo) {
        return r.alteracoes().stream().filter(a -> a.tipo() == tipo).toList();
    }

    @Test
    void avisoDeProrrogacao() {
        Resultado r = analisar("""
                PREFEITURA MUNICIPAL DE ENGENHEIRO PAULO DE FRONTIN
                AVISO DE PRORROGAÇÃO DE INSCRIÇÕES
                A Prefeitura torna pública a prorrogação das inscrições do Concurso Público regido pelo Edital nº 01/2026,
                previstas até 17/09/2026, que ficam prorrogadas até 24/09/2026, com pagamento do boleto até 25/09/2026.
                """);

        assertThat(r.titulo()).isEqualTo("AVISO DE PRORROGAÇÃO DE INSCRIÇÕES");
        List<Alteracao> prazo = doTipo(r, Tipo.PRAZO_INSCRICAO);
        assertThat(prazo).extracting(Alteracao::valor).containsExactly("2026-09-24");
        assertThat(prazo.getFirst().evidencia().pagina()).isEqualTo(1);
        assertThat(prazo.getFirst().evidencia().trecho()).contains("24/09/2026");
    }

    @Test
    void provaAdiada() {
        Resultado r = analisar("""
                COMUNICADO Nº 03
                A prova objetiva, prevista para 22/11/2026, fica adiada para o dia 06/12/2026, em razão das eleições.
                """);

        assertThat(doTipo(r, Tipo.DATA_PROVA)).extracting(Alteracao::valor).containsExactly("2026-12-06");
        assertThat(doTipo(r, Tipo.PRAZO_INSCRICAO)).isEmpty();
    }

    @Test
    void ondeSeLeLeiaSe() {
        Resultado r = analisar("""
                EDITAL DE RETIFICAÇÃO Nº 02/2026
                1. Fica retificado o item 3.1 do Edital nº 01/2026:
                Onde se lê: 3.1 As inscrições serão realizadas no período de 14/08/2026 a 17/09/2026.
                Leia-se: 3.1 As inscrições serão realizadas no período de 14/08/2026 a 24/09/2026.

                2. Fica retificado o requisito do cargo 105:
                Onde se lê: Curso de Segurança do Trabalho
                Leia-se: Curso Técnico em Segurança do Trabalho com registro no órgão de classe
                3. As demais disposições do edital permanecem inalteradas.
                """);

        List<Alteracao> trechos = doTipo(r, Tipo.TRECHO_ALTERADO);
        assertThat(trechos).hasSize(2);
        assertThat(trechos.get(1).antes()).isEqualTo("Curso de Segurança do Trabalho");
        assertThat(trechos.get(1).valor()).isEqualTo("Curso Técnico em Segurança do Trabalho com registro no órgão de classe");
        // o novo texto do item 3.1 também vira proposta de prazo
        assertThat(doTipo(r, Tipo.PRAZO_INSCRICAO)).extracting(Alteracao::valor).containsExactly("2026-09-24");
    }

    @Test
    void suspensaoDeclaradaSimClausulaHipoteticaNao() {
        assertThat(doTipo(analisar("""
                Os concursos poderão ser cancelados ou suspensos a critério da Administração, se houver interesse público.
                """), Tipo.SITUACAO)).isEmpty();

        Resultado r = analisar("""
                AVISO
                Fica SUSPENSO, por tempo indeterminado, o Concurso Público regido pelo Edital nº 01/2026, por decisão judicial.
                """);
        assertThat(doTipo(r, Tipo.SITUACAO)).extracting(Alteracao::valor).containsExactly("SUSPENSO");
    }

    @Test
    void cronogramaRepublicadoSemDizerProrroga() {
        Resultado r = analisar("""
                EDITAL DE RETIFICAÇÃO Nº 01 – CRONOGRAMA
                As inscrições serão realizadas no período de 01/09/2026 a 20/10/2026.
                29/11/26 Realização das Provas: Objetiva de Múltipla Escolha
                """);

        assertThat(doTipo(r, Tipo.PRAZO_INSCRICAO)).extracting(Alteracao::valor).containsExactly("2026-10-20");
        assertThat(doTipo(r, Tipo.DATA_PROVA)).extracting(Alteracao::valor).containsExactly("2026-11-29");
    }

    @Test
    void retificacaoRealDeFrontinComNovaRedacaoEmTabela() {
        // Retificação I ao Edital nº 01/2026 de Engenheiro Paulo de Frontin (paulodefrontin.rj.gov.br), p. 1
        Resultado r = analisador.analisar(new DocumentoLido(List.of("""
                PREFEITURA MUNICIPAL DE ENGENHEIRO PAULO DE FRONTIN – RJ
                CONCURSO PÚBLICO
                EDITAL Nº 01/2026, DE 13 DE AGOSTO DE 2026

                RETIFICAÇÃO I AO EDITAL Nº 01/2026, DE 13 DE AGOSTO DE 2026 - EDITAL DE ABERTURA

                O Prefeito Municipal, no uso de suas atribuições legais, retifica o Edital nº 01/2026 na forma seguinte.

                1 - A remuneração dos cargos de que trata o subitem 2.2 do Edital nº 1/2026 passa a ser a seguinte:

                Descrição do Cargo Remuneração
                Técnico de Enfermagem R$ 3.395,96
                Fiscal de Obras R$ 3.180,04
                Fiscal de Postura R$ 3.180,04

                2 - O subitem 3.3 do Edital nº 1/2026 passa a ter a seguinte redação:
                """), List.of()), List.of("Fiscal de Obras", "Técnico de Segurança"));

        assertThat(r.titulo()).startsWith("RETIFICAÇÃO I AO EDITAL Nº 01/2026");
        Alteracao remuneracao = doTipo(r, Tipo.NOVA_REDACAO).getFirst();
        assertThat(remuneracao.antes()).startsWith("1 - A remuneração dos cargos").endsWith("passa a ser a seguinte");
        assertThat(remuneracao.valor()).isEqualTo("""
                Descrição do Cargo Remuneração
                Técnico de Enfermagem R$ 3.395,96
                Fiscal de Obras R$ 3.180,04
                Fiscal de Postura R$ 3.180,04""");
        assertThat(r.cargosCitados()).extracting(AnalisadorAlteracoes.CargoCitado::nome).containsExactly("Fiscal de Obras");
        assertThat(r.cargosCitados().getFirst().evidencia().trecho()).contains("Fiscal de Obras R$ 3.180,04");
        assertThat(doTipo(r, Tipo.PRAZO_INSCRICAO)).isEmpty();
    }
}
