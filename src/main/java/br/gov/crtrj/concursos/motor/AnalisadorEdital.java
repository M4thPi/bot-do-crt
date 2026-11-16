package br.gov.crtrj.concursos.motor;

import br.gov.crtrj.concursos.motor.catalogo.CatalogoFormacoes;
import br.gov.crtrj.concursos.motor.classificacao.MotorClassificacao;
import br.gov.crtrj.concursos.motor.classificacao.ResultadoClassificacao;
import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.MetodoExtracao;
import br.gov.crtrj.concursos.motor.evidencia.VerificadorEvidencias;
import br.gov.crtrj.concursos.motor.extracao.AnaliseRequisito;
import br.gov.crtrj.concursos.motor.extracao.AnalisadorRequisitos;
import br.gov.crtrj.concursos.motor.extracao.CargoDetectado;
import br.gov.crtrj.concursos.motor.extracao.DadosCargo;
import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;
import br.gov.crtrj.concursos.motor.extracao.DetectorCargos;
import br.gov.crtrj.concursos.motor.extracao.ExtratorConcurso;
import br.gov.crtrj.concursos.motor.extracao.ExtratorConselho;
import br.gov.crtrj.concursos.motor.extracao.ExtratorDadosCargo;
import br.gov.crtrj.concursos.motor.extracao.Faixa;
import br.gov.crtrj.concursos.motor.extracao.FormacaoEncontrada;
import br.gov.crtrj.concursos.motor.extracao.InfoConselho;
import br.gov.crtrj.concursos.motor.leitor.DocumentoLido;
import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;
import br.gov.crtrj.concursos.motor.texto.LimpadorTexto;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.motor.texto.TextoDocumento;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Pipeline completo sobre um documento: leitura → estrutura → cargos → requisitos → conselho →
 * valores → verificação das evidências → classificação. Sem dependência de Spring.
 */
public final class AnalisadorEdital {

    public static final String VERSAO_EXTRATOR = "0.1.0";

    private final LeitorPdf leitor = new LeitorPdf();
    private final DetectorCargos detector = new DetectorCargos();
    private final AnalisadorRequisitos requisitos;
    private final ExtratorConselho extratorConselho = new ExtratorConselho();
    private final ExtratorDadosCargo extratorDadosCargo = new ExtratorDadosCargo();
    private final ExtratorConcurso extratorConcurso = new ExtratorConcurso();
    private final VerificadorEvidencias verificador = new VerificadorEvidencias();
    private final MotorClassificacao motor = new MotorClassificacao();

    public AnalisadorEdital(CatalogoFormacoes catalogo) {
        this.requisitos = new AnalisadorRequisitos(catalogo);
    }

    public ResultadoAnalise analisarPdf(byte[] pdf, OrigemDocumento origem) throws IOException {
        return analisar(leitor.ler(pdf), sha256(pdf), origem);
    }

    public ResultadoAnalise analisar(DocumentoLido lido, String sha256, OrigemDocumento origem) {
        TextoDocumento texto = TextoDocumento.de(LimpadorTexto.limpar(lido.paginasBrutas()));
        List<String> avisos = new ArrayList<>();
        if (!lido.paginasSemTexto().isEmpty()) {
            avisos.add("Páginas sem camada de texto (provável digitalização; OCR ainda não disponível): "
                    + lido.paginasSemTexto());
        }
        if (!origem.fonteOficial()) {
            avisos.add("Documento de fonte não confirmada como oficial (" + origem.tipoFonte()
                    + "): nenhuma vaga será classificada como COMPATÍVEL até o documento oficial ser analisado.");
        }

        DadosConcurso concurso = verificar(texto, extratorConcurso.extrair(texto));
        String nomeDocumento = concurso.numeroEdital().presente()
                ? "Edital nº " + concurso.numeroEdital().valor() : origem.nome();
        MotorClassificacao.Contexto ctx = new MotorClassificacao.Contexto(
                nomeDocumento, origem.fonteOficial(), lido.majoritariamenteEscaneado());

        List<CargoAnalisado> cargos = new ArrayList<>();
        int descartados = 0;
        List<CargoDetectado> detectados = detector.detectar(texto);
        List<int[]> areasDeCargos = detectados.stream()
                .flatMap(c -> c.blocos().stream())
                .map(b -> new int[]{b.inicio(), b.fim()})
                .toList();
        for (CargoDetectado detectado : detectados) {
            List<Faixa> faixas = requisitos.faixas(texto, detectado);
            AnaliseRequisito req = verificar(texto, requisitos.analisar(texto, faixas));
            InfoConselho conselho = verificar(texto, extratorConselho.analisar(texto, faixas, areasDeCargos));
            if (!candidato(detectado, req, conselho)) {
                descartados++;
                continue;
            }
            DadosCargo dados = verificar(texto, extratorDadosCargo.extrair(texto, detectado));
            ResultadoClassificacao classificacao = motor.classificar(req, conselho, ctx);
            String escolaridadeNorm = req.escolaridade().presente() ? Normalizador.normalizar(req.escolaridade().valor()) : null;

            cargos.add(new CargoAnalisado(
                    verificador.verificar(texto, Campo.encontrado(detectado.nome(),
                            texto.evidencia(detectado.inicioNome(), detectado.fimNome(), MetodoExtracao.REGEX))),
                    especialidade(texto, detectado),
                    req.escolaridade(), req.formacoes(), req.trecho(), conselho, dados,
                    ExtratorConcurso.taxaParaCargo(concurso.taxas(), escolaridadeNorm),
                    classificacao.classificacao(), classificacao.motivo(), classificacao.evidencia(),
                    classificacao.revisaoNecessaria(), motor.alertas(req, conselho)));
        }
        if (detectados.isEmpty()) {
            avisos.add("Nenhum bloco de cargo foi identificado. O edital pode usar um layout de tabela ainda não"
                    + " suportado; revise manualmente.");
        }
        cargos.sort(Comparator.comparing(CargoAnalisado::classificacao).thenComparing(c -> c.nome().valorOu("")));

        return new ResultadoAnalise(
                new ResultadoAnalise.DocumentoAnalisado(nomeDocumento, origem.url(), sha256, texto.totalPaginas(),
                        lido.paginasSemTexto(), origem.fonteOficial(), origem.tipoFonte()),
                concurso, cargos, descartados, avisos, VERSAO_EXTRATOR, OffsetDateTime.now());
    }

    /** Só entram cargos com algum sinal de nível técnico — o título sozinho basta para entrar, nunca para ser compatível. */
    private static boolean candidato(CargoDetectado cargo, AnaliseRequisito req, InfoConselho conselho) {
        return Normalizador.normalizar(cargo.nome()).contains("tecnic")
                || !req.formacoes().isEmpty()
                || req.tecnicoGenerico() != null
                || conselho.exigeSistemaCftCrt();
    }

    private Campo<String> especialidade(TextoDocumento texto, CargoDetectado cargo) {
        if (cargo.especialidade() == null || cargo.especialidade().isBlank()) {
            return Campo.naoEncontrado();
        }
        int inicio = cargo.inicioEspecialidade();
        Evidencia ev = texto.evidencia(inicio, inicio + Math.min(cargo.especialidade().length(), 40), MetodoExtracao.REGEX);
        return verificador.verificar(texto, Campo.encontrado(cargo.especialidade(), ev));
    }

    // ---------------------------------------------------------------- verificação

    private <T> Campo<T> v(TextoDocumento texto, Campo<T> campo) {
        return verificador.verificar(texto, campo);
    }

    private DadosConcurso verificar(TextoDocumento t, DadosConcurso d) {
        return new DadosConcurso(v(t, d.orgao()), v(t, d.numeroEdital()), v(t, d.banca()), v(t, d.esfera()),
                v(t, d.uf()), v(t, d.municipio()), v(t, d.inscricaoInicio()), v(t, d.inscricaoFim()),
                d.taxas().stream().map(x -> new DadosConcurso.Taxa(v(t, x.valor()), x.contexto()))
                        .filter(x -> x.valor().presente()).toList(),
                v(t, d.isencao()), v(t, d.isencaoFim()), v(t, d.siteInscricao()), v(t, d.dataProva()),
                d.etapas().stream().map(e -> v(t, e)).filter(Campo::presente).toList());
    }

    private AnaliseRequisito verificar(TextoDocumento t, AnaliseRequisito r) {
        List<FormacaoEncontrada> formacoes = r.formacoes().stream()
                .map(f -> {
                    Evidencia ev = verificador.verificar(t, f.evidencia());
                    return ev == null ? null : new FormacaoEncontrada(f.entrada(), f.textoLiteral(), ev);
                })
                .filter(f -> f != null)
                .toList();
        return new AnaliseRequisito(r.localizado(), r.rotulado(), verificador.verificar(t, r.trecho()), formacoes,
                verificador.verificar(t, r.tecnicoGenerico()), r.qualquerArea(), v(t, r.escolaridade()),
                verificador.verificar(t, r.qualificacao()), r.naoCatalogadas());
    }

    private InfoConselho verificar(TextoDocumento t, InfoConselho c) {
        Campo<String> literal = v(t, c.literal());
        if (!literal.presente() && c.literal().presente()) {
            return InfoConselho.naoIdentificado();
        }
        return new InfoConselho(c.exigido(), literal, c.tipo(), c.exigeCrtRj(), c.registroAtivo(), c.momento(),
                c.exigenciaGenerica());
    }

    private DadosCargo verificar(TextoDocumento t, DadosCargo d) {
        return new DadosCargo(v(t, d.vagasTotal()), v(t, d.vagasImediatas()), v(t, d.vagasAmpla()), v(t, d.vagasPcd()),
                v(t, d.cadastroReserva()), v(t, d.salarioBase()), v(t, d.remuneracaoTotal()), v(t, d.valorQuadro()),
                v(t, d.cargaHorariaSemanal()), v(t, d.cnh()), v(t, d.experiencia()));
    }

    public static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
