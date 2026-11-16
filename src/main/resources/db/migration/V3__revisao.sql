-- Revisão humana antes de publicar: só cargo APROVADA aparece na página pública.
-- classificacao_publicada: o que a página pública mostra (a do motor, ou a corrigida pelo revisor).
-- justificativa_revisor: obrigatória quando o revisor muda a classificação do motor; aparece na página pública.
-- revisao_obs continua sendo anotação interna.

alter table cargo add column classificacao_publicada varchar(40);
alter table cargo add column justificativa_revisor varchar(2000);
create index ix_cargo_revisao on cargo (revisao_status);
