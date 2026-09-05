package dtm.ide.spring.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BuiltInProperties {

    static final Map<String, SpringConfigProperty> INDEX = build();

    private BuiltInProperties() {
    }

    private static Map<String, SpringConfigProperty> build() {
        Map<String, SpringConfigProperty> index = new LinkedHashMap<>();

        put(index, "server.port", "java.lang.Integer",
                "Porta HTTP do servidor.", "8080");
        put(index, "server.address", "java.lang.String",
                "Endereco de rede em que o servidor escuta.", "");
        put(index, "server.servlet.context-path", "java.lang.String",
                "Prefixo de contexto da aplicacao.", "/");
        put(index, "server.error.include-message", "java.lang.String",
                "Quando incluir a mensagem de erro na resposta.", "never");
        put(index, "server.compression.enabled", "java.lang.Boolean",
                "Habilita compressao das respostas.", "false");

        put(index, "spring.application.name", "java.lang.String",
                "Nome da aplicacao, usado em logs e no registro de servicos.", "");
        put(index, "spring.profiles.active", "java.lang.String",
                "Perfis ativos, separados por virgula.", "");
        put(index, "spring.profiles.include", "java.util.List",
                "Perfis sempre incluidos, alem dos ativos.", "");
        put(index, "spring.config.import", "java.util.List",
                "Arquivos de configuracao adicionais a importar.", "");
        put(index, "spring.main.banner-mode", "java.lang.String",
                "Como exibir o banner na inicializacao.", "console");
        put(index, "spring.main.web-application-type", "java.lang.String",
                "Tipo da aplicacao web: servlet, reactive ou none.", "");

        put(index, "spring.datasource.url", "java.lang.String",
                "URL JDBC do banco de dados.", "");
        put(index, "spring.datasource.username", "java.lang.String",
                "Usuario do banco de dados.", "");
        put(index, "spring.datasource.password", "java.lang.String",
                "Senha do banco de dados.", "");
        put(index, "spring.datasource.driver-class-name", "java.lang.String",
                "Driver JDBC; normalmente deduzido da URL.", "");
        put(index, "spring.datasource.hikari.maximum-pool-size", "java.lang.Integer",
                "Numero maximo de conexoes no pool.", "10");
        put(index, "spring.datasource.hikari.minimum-idle", "java.lang.Integer",
                "Conexoes ociosas mantidas no pool.", "");
        put(index, "spring.datasource.hikari.connection-timeout", "java.lang.Long",
                "Tempo maximo de espera por uma conexao, em milissegundos.", "30000");

        put(index, "spring.jpa.hibernate.ddl-auto", "java.lang.String",
                "Estrategia de geracao do schema: none, validate, update, create, create-drop.", "");
        put(index, "spring.jpa.show-sql", "java.lang.Boolean",
                "Registra no log o SQL executado.", "false");
        put(index, "spring.jpa.properties.hibernate.format_sql", "java.lang.Boolean",
                "Formata o SQL registrado no log.", "false");
        put(index, "spring.jpa.open-in-view", "java.lang.Boolean",
                "Mantem a sessao JPA aberta durante a renderizacao da resposta.", "true");
        put(index, "spring.jpa.database-platform", "java.lang.String",
                "Dialeto do Hibernate.", "");

        put(index, "spring.flyway.enabled", "java.lang.Boolean",
                "Executa as migracoes do Flyway na inicializacao.", "true");
        put(index, "spring.flyway.locations", "java.util.List",
                "Onde procurar os scripts de migracao.", "classpath:db/migration");
        put(index, "spring.liquibase.change-log", "java.lang.String",
                "Arquivo de changelog do Liquibase.", "");

        put(index, "spring.mvc.pathmatch.matching-strategy", "java.lang.String",
                "Estrategia de casamento de rotas.", "path_pattern_parser");
        put(index, "spring.jackson.serialization.indent-output", "java.lang.Boolean",
                "Formata o JSON de saida.", "false");
        put(index, "spring.jackson.default-property-inclusion", "java.lang.String",
                "Quais propriedades incluir na serializacao.", "");
        put(index, "spring.servlet.multipart.max-file-size", "java.lang.String",
                "Tamanho maximo de um arquivo enviado.", "1MB");
        put(index, "spring.servlet.multipart.max-request-size", "java.lang.String",
                "Tamanho maximo total de uma requisicao com upload.", "10MB");

        put(index, "spring.security.user.name", "java.lang.String",
                "Usuario padrao em memoria.", "user");
        put(index, "spring.security.user.password", "java.lang.String",
                "Senha do usuario padrao.", "");

        put(index, "spring.cache.type", "java.lang.String",
                "Implementacao de cache a usar.", "");
        put(index, "spring.data.redis.host", "java.lang.String",
                "Host do Redis.", "localhost");
        put(index, "spring.data.redis.port", "java.lang.Integer",
                "Porta do Redis.", "6379");
        put(index, "spring.kafka.bootstrap-servers", "java.util.List",
                "Enderecos iniciais do cluster Kafka.", "localhost:9092");
        put(index, "spring.rabbitmq.host", "java.lang.String",
                "Host do RabbitMQ.", "localhost");

        put(index, "logging.level", "java.util.Map",
                "Nivel de log por pacote, ex.: logging.level.com.exemplo=DEBUG.", "");
        put(index, "logging.file.name", "java.lang.String",
                "Arquivo de log.", "");
        put(index, "logging.pattern.console", "java.lang.String",
                "Padrao das linhas de log no console.", "");

        put(index, "management.endpoints.web.exposure.include", "java.util.List",
                "Endpoints do Actuator expostos por HTTP.", "health");
        put(index, "management.endpoints.web.base-path", "java.lang.String",
                "Prefixo dos endpoints do Actuator.", "/actuator");
        put(index, "management.endpoint.health.show-details", "java.lang.String",
                "Quando detalhar o health check.", "never");
        put(index, "management.server.port", "java.lang.Integer",
                "Porta separada para o Actuator.", "");

        put(index, "spring.devtools.restart.enabled", "java.lang.Boolean",
                "Reinicia a aplicacao quando as classes mudam.", "true");
        put(index, "spring.devtools.livereload.enabled", "java.lang.Boolean",
                "Notifica o navegador para recarregar.", "true");

        return Map.copyOf(index);
    }

    private static void put(Map<String, SpringConfigProperty> index, String name, String type,
                            String description, String defaultValue) {
        index.put(name, new SpringConfigProperty(name, type, description, defaultValue,
                "", false, "", List.of()));
    }
}
