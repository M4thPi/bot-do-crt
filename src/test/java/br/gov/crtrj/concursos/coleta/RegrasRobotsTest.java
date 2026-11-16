package br.gov.crtrj.concursos.coleta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegrasRobotsTest {

    private static final String ROBO = "ConcursosTecnicosBot";

    @Test
    void grupoGeralComCuringa() {
        // robots.txt real do Selecon (selecon.selecao.net.br)
        RegrasRobots r = RegrasRobots.ler("""
                User-agent: *
                Disallow: /admin/*
                Disallow: /painel/*
                Disallow: /uploads/*
                Sitemap: https://selecon.selecao.net.br/sitemap.xml
                """, ROBO);

        assertThat(r.permite("/informacoes/65/")).isTrue();
        assertThat(r.permite("/uploads/746/concursos/65/anexos/edital.pdf")).isFalse();
        assertThat(r.permite("/robots.txt")).isTrue();
    }

    @Test
    void disallowTotalEVazio() {
        assertThat(RegrasRobots.ler("User-agent: *\nDisallow: /", ROBO).permite("/qualquer.pdf")).isFalse();
        assertThat(RegrasRobots.ler("User-agent: *\nDisallow:", ROBO).permite("/qualquer.pdf")).isTrue();
        assertThat(RegrasRobots.ler("", ROBO).permite("/x")).isTrue();
    }

    @Test
    void regraMaisLongaVenceEAllowGanhaNoEmpate() {
        RegrasRobots r = RegrasRobots.ler("""
                User-agent: *
                Disallow: /wp-admin/
                Allow: /wp-admin/admin-ajax.php
                Disallow: /arquivos
                Allow: /arquivos
                """, ROBO);

        assertThat(r.permite("/wp-admin/edit.php")).isFalse();
        assertThat(r.permite("/wp-admin/admin-ajax.php")).isTrue();
        assertThat(r.permite("/arquivos/edital.pdf")).isTrue();
    }

    @Test
    void grupoComONomeDoRoboSubstituiOGeral() {
        String texto = """
                # comentário
                User-agent: GPTBot
                Disallow: /

                User-agent: *
                Disallow: /Candidato/

                User-agent: ConcursosTecnicosBot
                Disallow: /concursos/arquivos/
                """;
        RegrasRobots r = RegrasRobots.ler(texto, ROBO);

        assertThat(r.permite("/Candidato/Cadastro")).isTrue();   // a regra do "*" não vale para nós
        assertThat(r.permite("/concursos/arquivos/edital.pdf")).isFalse();
        assertThat(RegrasRobots.ler(texto, "OutroBot").permite("/Candidato/Cadastro")).isFalse();
    }

    @Test
    void variosAgentesNoMesmoGrupoEFimDeEndereco() {
        RegrasRobots r = RegrasRobots.ler("""
                User-agent: Googlebot
                User-agent: *
                Disallow: /*.pdf$
                """, ROBO);

        assertThat(r.permite("/editais/edital.pdf")).isFalse();
        assertThat(r.permite("/editais/edital.pdf?v=2")).isTrue();
        assertThat(r.permite("/editais/")).isTrue();
    }

    @Test
    void regiaoRj() {
        RegiaoRj rj = new RegiaoRj(List.of("UFRJ", "Petrobras"));

        assertThat(rj.termoCitado("PREFEITURA DE SÃO GONÇALO-RJ")).isEqualTo("rj");
        assertThat(rj.termoCitado("Concurso Público - 01/2026 Prefeitura Municipal de Queimados")).isEqualTo("queimados");
        assertThat(rj.termoCitado("Defensoria Pública do Estado do Rio de Janeiro")).isEqualTo("rio de janeiro");
        assertThat(rj.termoCitado("Transpetro 2026")).isNull();
        assertThat(rj.termoCitado("Petrobras 2026")).isEqualTo("petrobras");
        // outro estado com nome de município do RJ
        assertThat(rj.termoCitado("Prefeitura de São Gonçalo do Amarante - RN")).isNull();
        assertThat(rj.termoCitado("Prefeitura de Nova Petrópolis")).isNull();
        assertThat(rj.termoCitado("Câmara Municipal de Valença")).isNull();   // Valença também existe na BA
        // texto longo: município não conta (nome de rua, de pessoa)
        assertThat(rj.termoDoEstadoCitado("sede na Av. Duque de Caxias, 100, Brasília")).isNull();
        assertThat(rj.termoDoEstadoCitado("Ministério da Educação/Universidade Federal Fluminense/Pró-Reitoria"))
                .isEqualTo("fluminense");
    }
}
