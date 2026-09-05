# Java / Spring Orion Support

Plugin de desenvolvimento Java para a Orion IDE. Ele reúne edição inteligente com Eclipse JDT LS,
projetos Maven e Gradle, execução, depuração, testes e ferramentas Spring em uma única experiência.

## Principais recursos

- IntelliSense, autoimport, hover, assinatura de métodos e navegação por código.
- CodeLens para referências, execução, depuração, testes e injeções Spring.
- Refatorações, ações rápidas, geração de código e Safe Delete com busca de usos.
- Projetos Maven, Gradle Groovy/Kotlin e Java sem ferramenta de build.
- Painel **Problemas** com erros navegáveis do Java, Maven, Gradle e JUnit.
- Gerenciamento e provisionamento de JDKs, JDT LS e extensões Java.
- Execução em terminal PTY e configurações reutilizáveis por projeto.
- Depuração DAP, breakpoints condicionais, watches, avaliação e hot reload.
- Test Explorer para JUnit 4/5/6 e TestNG.
- Ferramentas Spring para beans, injeções, endpoints, propriedades e Actuator.
- Assistentes para criar projetos Java, Maven, Gradle e Spring Boot.

## Requisitos

- Orion IDE 1.0.0 ou posterior.
- Acesso à internet na primeira inicialização para baixar componentes que ainda não estejam
  instalados, como JDT LS, Java Debug Server e JDKs solicitadas pelo projeto.
- Um projeto com `pom.xml`, `build.gradle`, `build.gradle.kts` ou fontes Java.

Não é obrigatório configurar uma JDK manualmente. O plugin procura instalações locais e pode baixar
uma distribuição Temurin compatível. Para desenvolver o próprio plugin, consulte o
[guia de contribuição](docs/CONTRIBUTING.md).

## Instalação

### Pelo gerenciador de plugins

1. Abra o painel **Gerenciar plugins** na Orion IDE.
2. Localize **Java / Spring Orion Support** no catálogo.
3. Selecione **Instalar** e aguarde a conclusão.
4. Reinicie a Orion caso a IDE solicite.

### Instalando um JAR local

Use este fluxo para testar uma versão compilada localmente ou instalar um JAR recebido fora do
catálogo:

1. Abra o painel **Gerenciar plugins**.
2. Selecione **Instalar local**.
3. Escolha o arquivo `JavaOrionSupport-<versão>.jar`.
4. Confirme a instalação e aguarde o carregamento.
5. Reinicie a Orion caso a IDE solicite.

Para gerar somente o JAR, sem instalá-lo automaticamente:

```powershell
mvn clean package "-Dmaven.antrun.skip=true"
```

O artefato será criado em `target/JavaOrionSupport-1.0.0.jar`.

## Primeiros passos

1. Abra na Orion a pasta raiz do projeto Java.
2. Aguarde o indicador **Java: IntelliSense pronto**. Na primeira abertura, o provisionamento pode
   levar alguns minutos.
3. Use **Sincronizar projeto** depois de modificar dependências ou arquivos de build.
4. Escolha ou crie uma configuração no seletor de execução da barra superior.
5. Execute pelo botão **Run**, inicie pelo botão **Debug** ou use os CodeLens exibidos no editor.

Projetos Maven e Gradle usam primeiro o wrapper do próprio projeto (`mvnw` ou `gradlew`). Na ausência
dele, o plugin resolve uma instalação disponível.

## Painéis e ferramentas

| Painel | Finalidade |
|---|---|
| **Problemas** | Lista erros e avisos por arquivo. Duplo clique abre a linha correspondente. |
| **Build Tools** | Exibe módulos, tarefas, goals, plugins, dependências e perfis Maven/Gradle. |
| **Testes** | Descobre, executa e depura testes por pacote, classe ou método. |
| **Debug** | Mostra threads, pilha, variáveis, watches e controles de execução. |
| **Spring** | Apresenta beans, injeções, endpoints, propriedades e informações do Actuator. |
| **Dependências** | Pesquisa artefatos e atualiza `pom.xml` ou scripts Gradle. |
| **JDK Manager** | Seleciona, baixa e gerencia as JDKs usadas pelo projeto. |
| **TODO** | Agrupa marcadores configuráveis encontrados no código. |

Os painéis podem ser abertos pelo menu **Janela** da Orion. Erros de compilação e falhas de teste
abrem automaticamente o painel **Problemas**.

## IntelliSense e editor

O plugin oferece sugestões locais imediatamente e inicia o Eclipse JDT LS em segundo plano para os
recursos semânticos. Quando o servidor termina de importar o projeto, diagnósticos, CodeLens,
referências, refatorações e ações rápidas são atualizados automaticamente.

Ao excluir uma classe com **Safe Delete**, o plugin procura referências pelo JDT LS e também faz uma
verificação local de segurança. Se encontrar usos fora da seleção, permite visualizá-los ou cancelar
a exclusão.

## Build, execução e testes

As ações de build incluem `compile`, `rebuild`, `clean`, `test`, `package` e `install`. A saída
completa permanece disponível no terminal, enquanto os erros relevantes são convertidos em itens
navegáveis no painel **Problemas**.

Há configurações para aplicação Java, Spring Boot, JAR, Maven, Gradle, testes e JVM remota. Cada uma
pode definir módulo, JDK, argumentos da JVM, argumentos da aplicação, diretório de trabalho,
variáveis de ambiente e perfis.

O Test Explorer reconhece JUnit e TestNG, permite repetir falhas e abre diretamente a linha indicada
pela stack trace.

## Depuração e hot reload

A depuração usa o Java Debug Server por DAP e suporta execução passo a passo, breakpoints
condicionais, pausa por exceção, threads, call stack, variáveis, watches e avaliação de expressões.

O hot reload recompila o módulo e usa Hot Code Replace. Alterações no corpo de métodos geralmente
podem ser aplicadas. Inclusão ou remoção de campos, métodos e assinaturas normalmente exige reiniciar
a aplicação por limitação da JVM.

## Configurações

Nas preferências do plugin é possível ajustar:

- modo e memória do IntelliSense;
- JDK padrão e suporte a Lombok;
- formatação e organização de imports ao salvar;
- build offline e execução de testes antes do Run;
- modo de hot reload;
- marcadores do painel TODO;
- suporte Spring, CodeLens, Actuator e URL da aplicação.

## Solução de problemas

### O IntelliSense ainda não apareceu

Aguarde a mensagem **Java: IntelliSense pronto**. Se o projeto mudou enquanto era importado, use
**Sincronizar projeto** ou limpe os caches Java nas ações do plugin.

### Dependências ou classes não foram reconhecidas

Confirme que o `pom.xml` ou script Gradle está salvo e sincronize o projeto. Em projetos com wrapper,
verifique se `mvnw`, `gradlew` e seus arquivos auxiliares estão presentes.

### O build falhou, mas o editor não mostrou o motivo

Abra **Janela → Problemas**. O painel reúne os erros por arquivo e mantém a saída integral no painel
de build para investigação adicional.

### O plugin não foi carregado

Abra **Gerenciar plugins** e confirme que **Java / Spring Orion Support** aparece instalado e
habilitado. Para uma compilação local, use **Instalar local**, selecione o JAR sombreado gerado pelo
Maven e reinicie a Orion caso seja solicitado.

## Contribuição

Contribuições são bem-vindas. Antes de enviar uma alteração, leia o
[Guia de contribuição](docs/CONTRIBUTING.md), que descreve preparação do ambiente, arquitetura,
comandos de validação, internacionalização e checklist de pull request.

Ao relatar um defeito, informe a versão da Orion, a JDK, o sistema operacional, a ferramenta de
build e um projeto mínimo ou os passos necessários para reproduzir o comportamento.

## Licença

Distribuído sob a [licença MIT](LICENSE).
