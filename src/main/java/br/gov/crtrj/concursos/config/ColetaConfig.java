package br.gov.crtrj.concursos.config;

import br.gov.crtrj.concursos.coleta.ClienteWeb;
import br.gov.crtrj.concursos.coleta.ClienteWebHttp;
import br.gov.crtrj.concursos.coleta.FonteBancas;
import br.gov.crtrj.concursos.coleta.FonteNoticias;
import br.gov.crtrj.concursos.coleta.FontePci;
import br.gov.crtrj.concursos.coleta.LocalizadorEditalOficial;
import br.gov.crtrj.concursos.coleta.RegiaoRj;
import br.gov.crtrj.concursos.coleta.dou.ClienteInlabsHttp;
import br.gov.crtrj.concursos.coleta.dou.FonteDou;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.persistencia.EdicaoDiarioRepositorio;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Configuration
@EnableScheduling
public class ColetaConfig {

    @Bean
    ClienteWeb clienteWeb(ConcursosProperties props) {
        return new ClienteWebHttp(props.download().userAgent(), Duration.ofSeconds(props.download().timeoutSegundos()),
                props.download().tamanhoMaximoMb() * 1024 * 1024, props.coleta().intervaloPorSiteMs(),
                props.coleta().semAcessoAutomatico());
    }

    @Bean
    RegiaoRj regiaoRj(ConcursosProperties props) {
        return new RegiaoRj(props.coleta().orgaosRj());
    }

    // a ordem dos beans é a ordem em que as fontes são lidas em cada rodada

    @Bean
    @Order(1)
    FontePci fontePci(ClienteWeb web, ConcursosProperties props) {
        Set<String> ufs = new HashSet<>();
        props.coleta().ufs().forEach(uf -> ufs.add(uf.toUpperCase(Locale.ROOT)));
        return new FontePci(web, props.coleta().listagensPci(), ufs);
    }

    @Bean
    @Order(2)
    FonteBancas fonteBancas(ClienteWeb web, ConcursosProperties props, RegiaoRj regiao, Clock relogio) {
        List<FonteBancas.Banca> bancas = props.coleta().bancas().stream()
                .map(b -> new FonteBancas.Banca(b.nome(), b.listagens() == null ? List.of() : b.listagens(),
                        Pattern.compile(b.link()), b.todasNoRj()))
                .toList();
        return new FonteBancas(web, bancas, regiao, relogio);
    }

    @Bean
    @Order(3)
    FonteDou fonteDou(ConcursosProperties props, RegiaoRj regiao, EdicaoDiarioRepositorio edicoes, Clock relogio) {
        ConcursosProperties.Dou dou = props.dou() != null ? props.dou() : new ConcursosProperties.Dou(null, null, null, 0);
        FonteDou.Inlabs inlabs = dou.configurado()
                ? new ClienteInlabsHttp(dou.email().strip(), dou.senha(), props.download().userAgent(),
                Duration.ofSeconds(props.download().timeoutSegundos()))
                : null;
        return new FonteDou(inlabs, dou.secoes(), dou.diasParaTras(), regiao, edicoes, relogio);
    }

    @Bean
    @Order(4)
    FonteNoticias fonteNoticias(ClienteWeb web, ConcursosProperties props, RegiaoRj regiao, Clock relogio) {
        List<FonteNoticias.Portal> portais = props.coleta().portaisDeNoticias().stream()
                .map(p -> new FonteNoticias.Portal(p.nome(), p.feeds(), p.feedsRj()))
                .toList();
        return new FonteNoticias(web, portais, regiao, relogio);
    }

    @Bean
    LocalizadorEditalOficial localizadorEditalOficial(ClienteWeb web, ValidadorFontes fontes) {
        return new LocalizadorEditalOficial(web, fontes);
    }
}
