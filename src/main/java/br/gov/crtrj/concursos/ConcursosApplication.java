package br.gov.crtrj.concursos;

import br.gov.crtrj.concursos.cli.AnalisarEditalCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

// Não há usuários nem senhas: o acesso é só pela chave de API. Sem a exclusão, o Spring cria um usuário
// "user" com senha aleatória e a escreve no log.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ConcursosApplication {

    public static void main(String[] args) {
        // Sites oficiais às vezes não enviam o certificado intermediário; com isso o Java o busca
        // pelo endereço indicado no próprio certificado (AIA) e continua validando a cadeia inteira.
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
        if (args.length > 0 && args[0].equals("analisar")) {
            System.exit(AnalisarEditalCli.executar(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(ConcursosApplication.class, args);
    }
}
