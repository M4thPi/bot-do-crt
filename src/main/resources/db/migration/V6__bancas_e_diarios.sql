-- Etapa 5: concursos achados nos sites das bancas e no Diário Oficial da União (INLABS).

-- Texto do ato do diário oficial: o robô procura nele o site onde está o edital.
alter table pista add column texto text;

-- Edições do diário já lidas (uma linha por dia e seção), para não baixar a mesma edição duas vezes.
create table edicao_diario (
    fonte               varchar(20)   not null,
    data                date          not null,
    secao               varchar(10)   not null,
    situacao            varchar(20)   not null,
    atos_lidos          int           not null,
    pistas              int           not null,
    processada_em       timestamp     not null,
    primary key (fonte, data, secao)
);
