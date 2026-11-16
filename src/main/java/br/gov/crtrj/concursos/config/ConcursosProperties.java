package br.gov.crtrj.concursos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "concursos")
public record ConcursosProperties(String armazenamento, Download download, Fontes fontes, Coleta coleta,
                                  Acompanhamento acompanhamento, Dou dou,
                                  Api api) {

    public record Download(long tamanhoMaximoMb, long timeoutSegundos, String userAgent) {
    }

    public record Fontes(List<String> sufixosOficiais, List<String> dominiosBancas, List<String> dominiosOrgaos,
                         List<String> dominiosAgregadores) {
    }

    /**
     * @param habilitada             liga o robô agendado
     * @param atrasoInicial          espera após subir a aplicação antes da 1ª rodada
     * @param intervalo              tempo entre o fim de uma rodada e o início da próxima
     * @param listagensPci           páginas regionais do PCI a ler
     * @param ufs                    UFs a manter ("RJ", "NACIONAL")
     * @param maxEditaisPorExecucao  quantas pistas processar por rodada (educação com os sites)
     * @param horasEntreTentativas   espera antes de tentar de novo uma pista que falhou
     * @param maxTentativas          depois disso a pista fica parada, esperando revisão manual
     * @param intervaloPorSiteMs     pausa mínima entre dois acessos ao mesmo site
     * @param bancas                 sites de bancas lidos a cada rodada
     * @param orgaosRj               órgãos com sede ou vagas no RJ, além dos municípios (filtro de bancas e DOU)
     * @param semAcessoAutomatico    domínios onde o robô não baixa nada, além do que o robots.txt proíbe
     * @param portaisDeNoticias      portais de notícias lidos pelo feed RSS a cada rodada
     */
    public record Coleta(boolean habilitada, Duration atrasoInicial, Duration intervalo, List<String> listagensPci,
                         List<String> ufs, int maxEditaisPorExecucao, int horasEntreTentativas, int maxTentativas,
                         long intervaloPorSiteMs, List<Banca> bancas, List<String> orgaosRj,
                         List<String> semAcessoAutomatico, List<Portal> portaisDeNoticias) {

        public Coleta {
            bancas = bancas == null ? List.of() : bancas;
            orgaosRj = orgaosRj == null ? List.of() : orgaosRj;
            semAcessoAutomatico = semAcessoAutomatico == null ? List.of() : semAcessoAutomatico;
            portaisDeNoticias = portaisDeNoticias == null ? List.of() : portaisDeNoticias;
        }
    }

    /**
     * Portal de notícias de concursos, lido pelo feed RSS. Só descoberta: o edital vem do site oficial.
     *
     * @param feeds   feeds com notícias do país todo (fica o que cita o RJ)
     * @param feedsRj feeds que o portal separa para o RJ (não precisam citar o estado)
     */
    public record Portal(String nome, List<String> feeds, List<String> feedsRj) {

        public Portal {
            feeds = feeds == null ? List.of() : feeds;
            feedsRj = feedsRj == null ? List.of() : feedsRj;
        }
    }

    /**
     * Site de banca organizadora.
     *
     * @param listagens páginas que listam os concursos
     * @param link      expressão regular que reconhece o endereço da página de um concurso
     * @param todasNoRj banca que só organiza concursos do RJ (o cartão não precisa citar o estado)
     */
    public record Banca(String nome, List<String> listagens, String link, boolean todasNoRj) {
    }

    /**
     * Diário Oficial da União pelo INLABS. Sem e-mail e senha, a fonte fica parada.
     *
     * @param secoes       seções lidas ("DO3" = editais e avisos)
     * @param diasParaTras na primeira leitura, quantos dias anteriores buscar
     */
    public record Dou(String email, String senha, List<String> secoes, int diasParaTras) {

        public Dou {
            secoes = secoes == null ? List.of("DO3") : secoes;
        }

        public boolean configurado() {
            return email != null && !email.isBlank() && senha != null && !senha.isBlank();
        }
    }

    /**
     * Acesso do site à API.
     *
     * @param chave              enviada pelo backend do site em {@code X-API-Key}; vazia = usa a guardada em
     *                           {@code arquivoChave} (gerada na 1ª execução)
     * @param arquivoChave       onde a chave gerada fica guardada
     * @param origensPermitidas  origens de navegador que podem ler as rotas públicas ({@code *} libera todas)
     */
    public record Api(String chave, String arquivoChave, List<String> origensPermitidas) {

        public Api {
            origensPermitidas = origensPermitidas == null ? List.of() : origensPermitidas;
        }
    }

    /**
     * Visitas à página oficial dos concursos já analisados, atrás de retificações e comunicados.
     *
     * @param intervalo                 tempo mínimo entre duas visitas ao mesmo concurso (sinal do PCI antecipa)
     * @param maxConcursosPorExecucao   quantos concursos visitar por rodada do robô
     * @param maxDocumentosPorConcurso  quantos PDFs novos baixar por visita
     * @param maxTentativas             falhas de download antes de desistir de um PDF
     * @param diasAposInscricaoSemProva sem data de prova, acompanha até N dias depois do fim das inscrições
     */
    public record Acompanhamento(Duration intervalo, int maxConcursosPorExecucao, int maxDocumentosPorConcurso,
                                 int maxTentativas, int diasAposInscricaoSemProva) {
    }
}
