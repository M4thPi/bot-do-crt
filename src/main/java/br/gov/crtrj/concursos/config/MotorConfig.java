package br.gov.crtrj.concursos.config;

import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.motor.AnalisadorEdital;
import br.gov.crtrj.concursos.motor.alteracoes.AnalisadorAlteracoes;
import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.relatorio.RenderizadorMarkdown;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** O motor de análise é Java puro; aqui ele é ligado ao Spring. */
@Configuration
public class MotorConfig {

    @Bean
    CatalogoFormacoes catalogoFormacoes() {
        return CatalogoFormacoes.carregarPadrao();
    }

    @Bean
    AnalisadorEdital analisadorEdital(CatalogoFormacoes catalogo) {
        return new AnalisadorEdital(catalogo);
    }

    @Bean
    AnalisadorAlteracoes analisadorAlteracoes() {
        return new AnalisadorAlteracoes();
    }

    @Bean
    ValidadorFontes validadorFontes(ConcursosProperties props) {
        ConcursosProperties.Fontes f = props.fontes();
        return new ValidadorFontes(f.sufixosOficiais(), f.dominiosBancas(), f.dominiosOrgaos(), f.dominiosAgregadores());
    }

    @Bean
    BaixadorDocumentos baixadorDocumentos(ConcursosProperties props) {
        ConcursosProperties.Download d = props.download();
        return new BaixadorDocumentos(d.tamanhoMaximoMb() * 1024 * 1024, Duration.ofSeconds(d.timeoutSegundos()), d.userAgent());
    }

    /** "Hoje" para status e filtros de data — substituível em testes. */
    @Bean
    Clock relogio() {
        return Clock.systemDefaultZone();
    }

    @Bean
    RenderizadorMarkdown renderizadorMarkdown() {
        return new RenderizadorMarkdown();
    }
}
