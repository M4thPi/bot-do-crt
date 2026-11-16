-- Dados que o revisor mandou não publicar (extração errada): chaves separadas por vírgula.
-- Na página pública eles aparecem como "Confira no edital."
alter table cargo add column campos_ocultos varchar(300);
