package br.gov.crtrj.concursos.servico;

import br.gov.crtrj.concursos.documentos.ArmazenamentoDocumentos;
import br.gov.crtrj.concursos.documentos.BaixadorDocumentos;
import br.gov.crtrj.concursos.documentos.TipoDocumento;
import br.gov.crtrj.concursos.fontes.ValidadorFontes;
import br.gov.crtrj.concursos.motor.AnalisadorEdital;
import br.gov.crtrj.concursos.motor.CargoAnalisado;
import br.gov.crtrj.concursos.motor.OrigemDocumento;
import br.gov.crtrj.concursos.motor.ResultadoAnalise;
import br.gov.crtrj.concursos.motor.dominio.Campo;
import br.gov.crtrj.concursos.motor.dominio.Evidencia;
import br.gov.crtrj.concursos.motor.dominio.StatusConcurso;
import br.gov.crtrj.concursos.motor.extracao.DadosCargo;
import br.gov.crtrj.concursos.motor.extracao.DadosConcurso;
import br.gov.crtrj.concursos.motor.extracao.FormacaoEncontrada;
import br.gov.crtrj.concursos.motor.leitor.LeitorPdf;
import br.gov.crtrj.concursos.motor.texto.Normalizador;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio;
import br.gov.crtrj.concursos.persistencia.AlteracaoRepositorio.AlteracaoLinha;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio;
import br.gov.crtrj.concursos.persistencia.CargoRepositorio.CargoLinha;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio;
import br.gov.crtrj.concursos.persistencia.ConcursoRepositorio.ConcursoLinha;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio;
import br.gov.crtrj.concursos.persistencia.DocumentoRepositorio.DocumentoLinha;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio;
import br.gov.crtrj.concursos.persistencia.EvidenciaRepositorio.EvidenciaLinha;
import br.gov.crtrj.concursos.relatorio.RenderizadorMarkdown;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recebe um edital (URL ou arquivo), analisa, guarda a cópia e grava concurso, cargos e
 * evidências. Se o concurso já existe, não duplica: registra o que mudou.
 */
@Service
public class ServicoIngestao {

    public record Resumo(long concursoId, long documentoId, boolean jaAnalisado, String concurso, boolean fonteOficial,
                         List<ResumoCargo> cargos, int cargosDescartados, List<String> avisos, List<String> alteracoes) {
    }

    public record ResumoCargo(long id, String nome, String classificacao, String motivo) {
    }

    private final AnalisadorEdital analisador;
    private final BaixadorDocumentos baixador;
    private final ValidadorFontes validador;
    private final ArmazenamentoDocumentos armazenamento;
    private final ConcursoRepositorio concursos;
    private final DocumentoRepositorio documentos;
    private final CargoRepositorio cargos;
    private final EvidenciaRepositorio evidencias;
    private final AlteracaoRepositorio alteracoes;
    private final ObjectMapper json;
    private final TransactionTemplate transacao;

    public ServicoIngestao(AnalisadorEdital analisador, BaixadorDocumentos baixador, ValidadorFontes validador,
                           ArmazenamentoDocumentos armazenamento, ConcursoRepositorio concursos,
                           DocumentoRepositorio documentos, CargoRepositorio cargos, EvidenciaRepositorio evidencias,
                           AlteracaoRepositorio alteracoes, ObjectMapper json, TransactionTemplate transacao) {
        this.analisador = analisador;
        this.baixador = baixador;
        this.validador = validador;
        this.armazenamento = armazenamento;
        this.concursos = concursos;
        this.documentos = documentos;
        this.cargos = cargos;
        this.evidencias = evidencias;
        this.alteracoes = alteracoes;
        this.json = json;
        this.transacao = transacao;
    }

    public Resumo ingerirUrl(String url, TipoDocumento tipo, boolean confirmadoOficial) throws IOException {
        BaixadorDocumentos.Download download = baixador.baixar(url);
        ValidadorFontes.TipoFonte fonte = confirmadoOficial
                ? ValidadorFontes.TipoFonte.CONFIRMADA_MANUALMENTE : validador.classificar(download.urlFinal());
        return ingerir(download.conteudo(), download.urlFinal(), nomeDaUrl(download.urlFinal()), tipo, fonte);
    }

    public Resumo ingerirArquivo(byte[] pdf, String nomeArquivo, String urlOrigem, TipoDocumento tipo,
                                 boolean confirmadoOficial) throws IOException {
        if (!LeitorPdf.pareceSerPdf(pdf)) {
            throw new IllegalArgumentException("O arquivo enviado não é um PDF.");
        }
        ValidadorFontes.TipoFonte fonte = confirmadoOficial ? ValidadorFontes.TipoFonte.CONFIRMADA_MANUALMENTE
                : validador.classificar(urlOrigem);
        String nome = nomeArquivo != null && !nomeArquivo.isBlank() ? nomeArquivo : "documento.pdf";
        return ingerir(pdf, blankParaNull(urlOrigem), nome, tipo, fonte);
    }

    private Resumo ingerir(byte[] pdf, String url, String nome, TipoDocumento tipo, ValidadorFontes.TipoFonte fonte)
            throws IOException {
        String sha = AnalisadorEdital.sha256(pdf);
        var existente = documentos.porSha256(sha);
        if (existente.isPresent()) {
            return resumoExistente(existente.get());
        }
        // Leitura e análise ficam fora da transação: são lentas e não tocam o banco.
        ResultadoAnalise resultado = analisador.analisarPdf(pdf, new OrigemDocumento(nome, url, fonte.oficial(), fonte.name()));
        Path arquivo = armazenamento.salvar(sha, pdf);
        String resultadoJson;
        try {
            resultadoJson = json.writeValueAsString(resultado);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a análise", e);
        }
        return Objects.requireNonNull(transacao.execute(status ->
                gravar(resultado, resultadoJson, tipo, arquivo, url, fonte)));
    }

    private Resumo gravar(ResultadoAnalise r, String resultadoJson, TipoDocumento tipo, Path arquivo, String url,
                          ValidadorFontes.TipoFonte fonte) {
        LocalDateTime agora = LocalDateTime.now();
        DadosConcurso k = r.concurso();
        String chave = chaveDedup(r);
        var anterior = concursos.porChave(chave);

        StatusConcurso status = StatusConcurso.porDatas(null, k.inscricaoInicio().valorOu(null),
                k.inscricaoFim().valorOu(null), k.dataProva().valorOu(null), LocalDate.now());
        ConcursoLinha nova = new ConcursoLinha(anterior.map(ConcursoLinha::id).orElse(null), chave,
                RenderizadorMarkdown.nomeConcurso(r), valor(k.orgao()), valor(k.numeroEdital()), valor(k.banca()),
                valor(k.esfera()), valor(k.uf()), valor(k.municipio()), status.name(),
                k.inscricaoInicio().valorOu(null), k.inscricaoFim().valorOu(null), k.dataProva().valorOu(null),
                valor(k.siteInscricao()), anterior.map(ConcursoLinha::encontradoEm).orElse(agora), agora, agora);

        long concursoId;
        boolean substituiCargos;
        List<Mudanca> mudancas = new ArrayList<>();
        if (anterior.isEmpty()) {
            concursoId = concursos.inserir(nova);
            substituiCargos = true;
        } else {
            concursoId = anterior.get().id();
            // Cópia de fonte secundária nunca substitui a análise feita sobre um documento oficial.
            boolean jaTemOficial = documentos.listarPorConcurso(concursoId).stream().anyMatch(DocumentoLinha::oficial);
            boolean rebaixaria = jaTemOficial && !fonte.oficial();
            substituiCargos = tipo == TipoDocumento.EDITAL && !rebaixaria;
            if (substituiCargos) {
                mudancas.addAll(diferencasConcurso(anterior.get(), nova));
                concursos.atualizar(nova);
            } else if (rebaixaria) {
                concursos.registrarVerificacao(concursoId, agora);
                mudancas.add(Mudanca.aviso("Novo documento", "versão de fonte não oficial (" + fonte + ") recebida e"
                        + " guardada; a análise vigente continua sendo a do documento oficial."));
            } else {
                concursos.registrarVerificacao(concursoId, agora);
                mudancas.add(Mudanca.aviso("Novo documento", tipo + " recebido. A aplicação automática de retificações"
                        + " ainda não está implementada: revise se ele altera vagas, salário, requisitos ou datas."));
            }
        }

        long documentoId = documentos.inserir(new DocumentoLinha(null, concursoId, tipo.name(), r.documento().nome(), url,
                fonte.name(), fonte.oficial(), r.documento().sha256(), arquivo.toString(), r.documento().paginas(),
                r.documento().paginasSemTexto().size(), r.versaoExtrator(), agora, r.analisadoEm().toLocalDateTime()), resultadoJson);
        gravarEvidenciasConcurso(documentoId, k, agora);

        List<ResumoCargo> resumo = new ArrayList<>();
        if (substituiCargos) {
            List<CargoLinha> antigos = cargos.listarPorConcurso(concursoId);
            List<CargoLinha> novos = new ArrayList<>();
            cargos.excluirPorConcurso(concursoId);
            for (int i = 0; i < r.cargos().size(); i++) {
                CargoAnalisado c = r.cargos().get(i);
                CargoLinha linha = decisaoMantida(linhaCargo(concursoId, documentoId, i, c), antigos, mudancas);
                long id = cargos.inserir(linha);
                gravarEvidenciasCargo(documentoId, id, c, agora);
                novos.add(linha);
                resumo.add(new ResumoCargo(id, c.nome().valor(), c.classificacao().name(), c.motivo()));
            }
            if (anterior.isPresent()) {
                mudancas.addAll(diferencasCargos(antigos, novos));
            }
        }
        for (Mudanca m : mudancas) {
            alteracoes.inserir(AlteracaoLinha.registro(concursoId, documentoId, limitar(m.campo(), 200),
                    limitar(m.antes(), 1000), limitar(m.depois(), 1000), agora));
        }
        return new Resumo(concursoId, documentoId, false, nova.nome(), fonte.oficial(), resumo, r.cargosDescartados(),
                r.avisos(), mudancas.stream().map(Mudanca::texto).toList());
    }

    private Resumo resumoExistente(DocumentoLinha d) {
        List<ResumoCargo> lista = cargos.listarPorConcurso(d.concursoId()).stream()
                .filter(c -> c.documentoId().equals(d.id()))
                .map(c -> new ResumoCargo(c.id(), c.nome(), c.classificacao(), c.motivo()))
                .toList();
        String nome = concursos.porId(d.concursoId()).map(ConcursoLinha::nome).orElse(d.nome());
        return new Resumo(d.concursoId(), d.id(), true, nome, d.oficial(), lista, 0,
                List.of("Documento idêntico (mesmo sha256) já analisado em " + d.analisadoEm() + "."), List.of());
    }

    /**
     * Nova versão do edital: a decisão do revisor (aprovado/rejeitado) continua valendo para o cargo cujos
     * dados publicáveis não mudaram. O que mudou volta para a fila — nada novo vai ao ar sem revisão.
     */
    private static CargoLinha decisaoMantida(CargoLinha nova, List<CargoLinha> antigos, List<Mudanca> mudancas) {
        CargoLinha antiga = antigos.stream().filter(a -> a.chave().equals(nova.chave())).findFirst().orElse(null);
        if (antiga == null || !List.of("APROVADA", "REJEITADA").contains(antiga.revisaoStatus())) {
            return nova;
        }
        if (!assinatura(antiga).equals(assinatura(nova))) {
            mudancas.add(Mudanca.aviso("Cargo de volta à revisão", nova.nome()
                    + ": dados diferentes na nova versão do edital (decisão anterior: " + antiga.revisaoStatus() + ")"));
            return nova;
        }
        return nova.comRevisaoDe(antiga);
    }

    /** Tudo o que a revisão aprova e a página pública mostra (sem páginas/itens, que mudam com a diagramação). */
    private static List<Object> assinatura(CargoLinha c) {
        return Arrays.asList(c.classificacao(), c.escolaridade(), c.formacoes(), c.conselhoExigido(),
                c.conselhoTipo(), c.conselhoLiteral(), c.exigeCrtRj(), c.registroAtivo(), c.momentoRegistro(),
                c.exigenciaGenerica(), c.vagasTotal(), c.vagasImediatas(), c.vagasAmpla(), c.vagasPcd(),
                c.cadastroReserva(), dinheiro(c.salarioBase()), dinheiro(c.remuneracaoTotal()), dinheiro(c.valorQuadro()),
                c.cargaHoraria(), dinheiro(c.taxaInscricao()));
    }

    private static String dinheiro(BigDecimal valor) {
        return valor == null ? null : valor.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- documentos posteriores ao edital

    /**
     * Edital consolidado ou republicado encontrado no acompanhamento. Só vira a nova versão do concurso se
     * for o mesmo concurso (mesmo órgão e nº) e tiver cargos; aí os cargos são substituídos e as decisões do
     * revisor ficam onde nada mudou. Vazio = não é nova versão (trate como documento comum).
     */
    public Optional<Resumo> ingerirNovaVersao(long concursoId, byte[] pdf, String url, String nome,
                                              ValidadorFontes.TipoFonte fonte) throws IOException {
        if (!LeitorPdf.pareceSerPdf(pdf) || documentos.porSha256(AnalisadorEdital.sha256(pdf)).isPresent()) {
            return Optional.empty();
        }
        ResultadoAnalise r = analisador.analisarPdf(pdf, new OrigemDocumento(nome, url, fonte.oficial(), fonte.name()));
        String chave = concursos.porId(concursoId).map(ConcursoLinha::chaveDedup).orElse(null);
        if (r.cargos().isEmpty() || !chaveDedup(r).equals(chave)) {
            return Optional.empty();
        }
        return Optional.of(ingerir(pdf, url, nome, TipoDocumento.EDITAL, fonte));
    }

    /** @param jaExistia o mesmo arquivo (sha256) já estava guardado; nada foi gravado */
    public record DocumentoRegistrado(long documentoId, long concursoId, boolean jaExistia, String nome) {
    }

    /**
     * Guarda uma retificação/comunicado já sabendo a que concurso pertence (o nº de uma retificação é
     * outro, então a chave órgão + nº não serve). Não mexe nos cargos: as mudanças viram propostas.
     */
    public DocumentoRegistrado registrarDocumentoDoConcurso(long concursoId, byte[] pdf, String url, String nome,
                                                           TipoDocumento tipo, ValidadorFontes.TipoFonte fonte)
            throws IOException {
        if (!LeitorPdf.pareceSerPdf(pdf)) {
            throw new IllegalArgumentException("O arquivo não é um PDF.");
        }
        String sha = AnalisadorEdital.sha256(pdf);
        var existente = documentos.porSha256(sha);
        if (existente.isPresent()) {
            return new DocumentoRegistrado(existente.get().id(), existente.get().concursoId(), true, existente.get().nome());
        }
        ResultadoAnalise r = analisador.analisarPdf(pdf, new OrigemDocumento(nome, url, fonte.oficial(), fonte.name()));
        Path arquivo = armazenamento.salvar(sha, pdf);
        String resultadoJson;
        try {
            resultadoJson = json.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar a análise", e);
        }
        LocalDateTime agora = LocalDateTime.now();
        long id = documentos.inserir(new DocumentoLinha(null, concursoId, tipo.name(), nome, url, fonte.name(),
                fonte.oficial(), sha, arquivo.toString(), r.documento().paginas(), r.documento().paginasSemTexto().size(),
                r.versaoExtrator(), agora, r.analisadoEm().toLocalDateTime()), resultadoJson);
        concursos.registrarVerificacao(concursoId, agora);
        return new DocumentoRegistrado(id, concursoId, false, nome);
    }

    // ---------------------------------------------------------------- mapeamento

    private static CargoLinha linhaCargo(long concursoId, long documentoId, int indice, CargoAnalisado c) {
        DadosCargo d = c.dados();
        String formacoes = c.formacoes().isEmpty() ? null
                : c.formacoes().stream().map(f -> f.entrada().id()).collect(Collectors.joining(";", ";", ";"));
        String nomesFormacoes = c.formacoes().isEmpty() ? null
                : c.formacoes().stream().map(f -> f.entrada().nome()).collect(Collectors.joining("; "));
        return new CargoLinha(null, concursoId, documentoId, indice, chaveCargo(c), c.nome().valor(), valor(c.especialidade()),
                valor(c.escolaridade()), formacoes, nomesFormacoes, c.conselho().exigido().name(),
                c.conselho().literal().presente() && c.conselho().tipo() != null ? c.conselho().tipo().name() : null,
                valor(c.conselho().literal()), c.conselho().exigeCrtRj().name(), c.conselho().registroAtivo().name(),
                c.conselho().momento().name(), c.conselho().exigenciaGenerica(),
                d.vagasTotal().valorOu(null), d.vagasImediatas().valorOu(null), d.vagasAmpla().valorOu(null),
                d.vagasPcd().valorOu(null), d.cadastroReserva().presente(),
                d.salarioBase().valorOu(null), d.remuneracaoTotal().valorOu(null), d.valorQuadro().valor(),
                d.cargaHorariaSemanal().valorOu(null), c.taxaInscricao().valor(),
                c.classificacao().name(), c.motivo(), c.revisaoNecessaria() ? "PENDENTE" : "NAO_NECESSARIA",
                null, null, null, null, null);
    }

    private void gravarEvidenciasConcurso(long documentoId, DadosConcurso k, LocalDateTime agora) {
        List<EvidenciaLinha> lista = new ArrayList<>();
        adicionar(lista, documentoId, null, "orgao", k.orgao(), agora);
        adicionar(lista, documentoId, null, "numero_edital", k.numeroEdital(), agora);
        adicionar(lista, documentoId, null, "banca", k.banca(), agora);
        adicionar(lista, documentoId, null, "esfera", k.esfera(), agora);
        adicionar(lista, documentoId, null, "uf", k.uf(), agora);
        adicionar(lista, documentoId, null, "municipio", k.municipio(), agora);
        adicionar(lista, documentoId, null, "inscricao_inicio", k.inscricaoInicio(), agora);
        adicionar(lista, documentoId, null, "inscricao_fim", k.inscricaoFim(), agora);
        adicionar(lista, documentoId, null, "isencao", k.isencao(), agora);
        adicionar(lista, documentoId, null, "isencao_fim", k.isencaoFim(), agora);
        adicionar(lista, documentoId, null, "site_inscricao", k.siteInscricao(), agora);
        adicionar(lista, documentoId, null, "data_prova", k.dataProva(), agora);
        k.taxas().forEach(t -> adicionar(lista, documentoId, null, "taxa_inscricao", t.valor(), agora));
        k.etapas().forEach(e -> adicionar(lista, documentoId, null, "etapa", e, agora));
        lista.forEach(evidencias::inserir);
    }

    private void gravarEvidenciasCargo(long documentoId, long cargoId, CargoAnalisado c, LocalDateTime agora) {
        List<EvidenciaLinha> lista = new ArrayList<>();
        DadosCargo d = c.dados();
        adicionar(lista, documentoId, cargoId, "nome", c.nome(), agora);
        adicionar(lista, documentoId, cargoId, "especialidade", c.especialidade(), agora);
        adicionar(lista, documentoId, cargoId, "escolaridade", c.escolaridade(), agora);
        adicionar(lista, documentoId, cargoId, "requisito", "", c.requisito(), "ENCONTRADO", agora);
        for (FormacaoEncontrada f : c.formacoes()) {
            adicionar(lista, documentoId, cargoId, "formacao", f.entrada().nome(), f.evidencia(), "ENCONTRADO", agora);
        }
        adicionar(lista, documentoId, cargoId, "conselho", c.conselho().literal(), agora);
        adicionar(lista, documentoId, cargoId, "vagas_total", d.vagasTotal(), agora);
        adicionar(lista, documentoId, cargoId, "vagas_imediatas", d.vagasImediatas(), agora);
        adicionar(lista, documentoId, cargoId, "vagas_ampla", d.vagasAmpla(), agora);
        adicionar(lista, documentoId, cargoId, "vagas_pcd", d.vagasPcd(), agora);
        adicionar(lista, documentoId, cargoId, "cadastro_reserva", d.cadastroReserva(), agora);
        adicionar(lista, documentoId, cargoId, "salario_base", d.salarioBase(), agora);
        adicionar(lista, documentoId, cargoId, "remuneracao_total", d.remuneracaoTotal(), agora);
        adicionar(lista, documentoId, cargoId, "valor_quadro", d.valorQuadro(), agora);
        adicionar(lista, documentoId, cargoId, "carga_horaria", d.cargaHorariaSemanal(), agora);
        adicionar(lista, documentoId, cargoId, "cnh", d.cnh(), agora);
        adicionar(lista, documentoId, cargoId, "experiencia", d.experiencia(), agora);
        adicionar(lista, documentoId, cargoId, "taxa_inscricao", c.taxaInscricao(), agora);
        adicionar(lista, documentoId, cargoId, "classificacao", c.classificacao().name(), c.evidenciaClassificacao(), "ENCONTRADO", agora);
        lista.forEach(evidencias::inserir);
    }

    private static void adicionar(List<EvidenciaLinha> lista, long documentoId, Long cargoId, String campo, Campo<?> c,
                                  LocalDateTime agora) {
        if (c.evidencia() != null) {
            adicionar(lista, documentoId, cargoId, campo, String.valueOf(c.valor()), c.evidencia(), c.status().name(), agora);
        }
    }

    private static void adicionar(List<EvidenciaLinha> lista, long documentoId, Long cargoId, String campo, String valor,
                                  Evidencia ev, String status, LocalDateTime agora) {
        if (ev != null) {
            lista.add(new EvidenciaLinha(null, documentoId, cargoId, campo, limitar(valor, 1000), status, ev.pagina(),
                    ev.secao(), ev.item(), limitar(ev.trecho(), 4000), ev.metodo().name(), ev.score(), agora));
        }
    }

    // ---------------------------------------------------------------- alterações

    private static List<Mudanca> diferencasConcurso(ConcursoLinha a, ConcursoLinha b) {
        List<Mudanca> lista = new ArrayList<>();
        comparar(lista, "Banca", a.banca(), b.banca());
        comparar(lista, "Início das inscrições", a.inscricaoInicio(), b.inscricaoInicio());
        comparar(lista, "Fim das inscrições", a.inscricaoFim(), b.inscricaoFim());
        comparar(lista, "Data da prova", a.dataProva(), b.dataProva());
        comparar(lista, "Site de inscrição", a.siteInscricao(), b.siteInscricao());
        return lista;
    }

    private static List<Mudanca> diferencasCargos(List<CargoLinha> antigos, List<CargoLinha> novos) {
        List<Mudanca> lista = new ArrayList<>();
        Map<String, CargoLinha> porChaveAntigo = antigos.stream()
                .collect(Collectors.toMap(CargoLinha::chave, Function.identity(), (x, y) -> x));
        Map<String, CargoLinha> porChaveNovo = novos.stream()
                .collect(Collectors.toMap(CargoLinha::chave, Function.identity(), (x, y) -> x));
        for (CargoLinha n : novos) {
            CargoLinha a = porChaveAntigo.get(n.chave());
            if (a == null) {
                lista.add(Mudanca.aviso("Cargo novo", n.nome()));
                continue;
            }
            String p = n.nome() + " – ";
            comparar(lista, p + "salário-base", a.salarioBase(), n.salarioBase());
            comparar(lista, p + "remuneração", a.remuneracaoTotal(), n.remuneracaoTotal());
            comparar(lista, p + "vagas", a.vagasTotal(), n.vagasTotal());
            comparar(lista, p + "formações", a.formacoes(), n.formacoes());
            comparar(lista, p + "conselho", a.conselhoLiteral(), n.conselhoLiteral());
            comparar(lista, p + "carga horária", a.cargaHoraria(), n.cargaHoraria());
            comparar(lista, p + "classificação", a.classificacao(), n.classificacao());
        }
        for (CargoLinha a : antigos) {
            if (!porChaveNovo.containsKey(a.chave())) {
                lista.add(Mudanca.aviso("Cargo não encontrado na nova versão", a.nome()));
            }
        }
        return lista;
    }

    /** Uma mudança entre a versão gravada e a nova. */
    /** Uma mudança entre a versão gravada e a nova; {@code comparacao=false} é um aviso sem antes/depois. */
    record Mudanca(String campo, String antes, String depois, boolean comparacao) {

        static Mudanca aviso(String campo, String detalhe) {
            return new Mudanca(campo, null, detalhe, false);
        }

        String texto() {
            if (!comparacao) {
                return campo + ": " + depois;
            }
            return campo + ": " + (antes == null ? "não identificado" : antes) + " → " + (depois == null ? "não identificado" : depois);
        }
    }

    private static void comparar(List<Mudanca> lista, String rotulo, Object antes, Object depois) {
        boolean iguais = antes instanceof BigDecimal x && depois instanceof BigDecimal y
                ? x.compareTo(y) == 0 : Objects.equals(antes, depois);
        if (!iguais) {
            lista.add(new Mudanca(rotulo, antes == null ? null : antes.toString(), depois == null ? null : depois.toString(), true));
        }
    }

    // ---------------------------------------------------------------- utilitários

    /** Mesmo órgão + mesmo nº de edital = mesmo concurso. Sem esses dados, o documento vira um concurso próprio. */
    static String chaveDedup(ResultadoAnalise r) {
        DadosConcurso k = r.concurso();
        if (k.orgao().presente() && k.numeroEdital().presente()) {
            return Normalizador.chave(k.orgao().valor()) + "|" + k.numeroEdital().valor();
        }
        return "sha:" + r.documento().sha256();
    }

    private static String chaveCargo(CargoAnalisado c) {
        return limitar(Normalizador.chave(c.nome().valor() + " " + c.especialidade().valorOu("")), 300);
    }

    private static String valor(Campo<String> c) {
        return c.valor();
    }

    private static String limitar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankParaNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String nomeDaUrl(String url) {
        String caminho = URI.create(url).getPath();
        if (caminho == null || caminho.isBlank() || caminho.equals("/")) {
            return url;
        }
        return caminho.substring(caminho.lastIndexOf('/') + 1);
    }
}
