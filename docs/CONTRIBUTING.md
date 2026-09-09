# Guia de contribuição

Obrigado por contribuir com o Java / Spring Orion Support. Este documento descreve como preparar o
ambiente, localizar os componentes e validar uma alteração antes de enviá-la.

## Preparação do ambiente

Você precisará de:

- Git;
- JDK 25;
- Maven 3.9 ou posterior;
- Orion IDE para validações manuais de integração.

Clone o repositório e entre no diretório do projeto:

```powershell
git clone https://github.com/DanielTM999/JavaOrionSupport.git
cd JavaOrionSupport
```

Execute a suíte antes de começar para confirmar que o ambiente está correto:

```powershell
mvn test
```

## Build local

Para compilar e gerar o plugin sem modificar a instalação local da Orion:

```powershell
mvn clean package "-Dmaven.antrun.skip=true"
```

O JAR resultante fica em `target/JavaOrionSupport-1.0.0.jar`.

O build sem `-Dmaven.antrun.skip=true` executa também a tarefa de implantação configurada no
`pom.xml`. Confira o diretório de destino antes de usá-lo, pois versões antigas do plugin podem ser
removidas durante essa etapa.

Para validar somente a compilação ou executar um teste específico:

```powershell
mvn -DskipTests compile
mvn "-Dtest=NomeDaClasseTest" test
```

## Teste manual na Orion

1. Gere o JAR com `mvn clean package "-Dmaven.antrun.skip=true"`.
2. Abra **Gerenciar plugins** na Orion.
3. Selecione **Instalar local** e escolha `target/JavaOrionSupport-1.0.0.jar`.
4. Confirme a instalação e reinicie a Orion caso seja solicitado.
5. Abra um projeto Java pequeno.
6. Verifique o recurso alterado e um fluxo relacionado para detectar regressões.

Para mudanças em LSP ou build, valide pelo menos um projeto Maven. Quando aplicável, valide também
Gradle e Java sem ferramenta de build. Mudanças de interface devem ser verificadas com os temas
claro e escuro.

## Organização do código

```text
src/main/java/dtm/ide/
├── build/       Maven, Gradle, javac e diagnósticos
├── coverage/    agente JaCoCo, leitura do exec e marcação na gutter
├── debug/       cliente DAP, sessão e hot reload
├── deps/        pesquisa e edição de dependências
├── editor/      completion, tema e tokenização
├── lsp/         JDT LS, bundles e protocolo LSP
├── project/     descoberta de projetos e módulos
├── refactor/    refatorações e verificações de segurança
├── run/         execução e configurações
├── sdk/         descoberta e provisionamento de JDKs
├── settings/    preferências do plugin
├── spring/      índice e ferramentas Spring
├── test/        descoberta, execução e relatórios de testes
├── todo/        scanner de marcadores TODO
├── ui/          painéis e componentes visuais
└── wizard/      criação de projetos
```

Outros diretórios relevantes:

- `src/main/resources/languages/`: traduções em português e inglês;
- `src/main/resources/imgs/`: ícones e imagens do plugin;
- `src/main/resources/META-INF/`: manifesto da coleção de plugins;
- `src/test/java/`: testes unitários e de integração local.

`JavaIdeAdapter` é o ponto de integração com a Orion. Prefira colocar regras de negócio nas classes
especializadas de cada pacote e manter o adapter responsável pela orquestração.

## Convenções de implementação

- Preserve o estilo e a organização do pacote que está sendo alterado.
- Não execute I/O, Maven, Gradle ou chamadas LSP demoradas na EDT do Swing.
- Faça atualizações de componentes Swing na EDT.
- Normalize caminhos antes de comparar arquivos do projeto.
- Mantenha operações de edição e exclusão conservadoras e recuperáveis.
- Adicione testes para correções de defeitos e para novas regras de negócio.
- Não grave caches, índices, arquivos da IDE ou artefatos de build no repositório.

### Interface e internacionalização

Textos visíveis ao usuário devem possuir chaves equivalentes em:

- `src/main/resources/languages/pt-BR.json`;
- `src/main/resources/languages/en-US.json`.

Reutilize `UiTokens` e os componentes visuais existentes para manter dimensões, espaçamento, cores e
comportamento consistentes com a Orion. Evite cores fixas quando houver um token semântico adequado.

### LSP e processos externos

O Eclipse JDT LS mantém estado por documento. Mudanças em abertura, edição, salvamento, renomeação ou
exclusão devem preservar a sequência correta de notificações LSP e invalidar os caches relacionados.

Ao executar processos externos:

- prefira o wrapper do projeto;
- encaminhe a saída progressivamente;
- respeite cancelamento e timeout;
- converta erros estruturados em diagnósticos navegáveis quando houver arquivo e linha.

## Testes

Execute a suíte completa antes de enviar a contribuição:

```powershell
mvn test
```

Para mudanças visuais, os testes automatizados não substituem a inspeção manual. Confira estados
vazio, carregando, sucesso e erro, além de filtro, atualização e navegação por teclado quando esses
comportamentos existirem.

Um teste de regressão deve reproduzir o defeito antes da correção e passar depois dela. Evite testes
dependentes da instalação pessoal da JDK, da rede ou de caminhos absolutos; use diretórios temporários
e fixtures pequenas.

## Enviando uma alteração

1. Crie uma branch curta e focada.
2. Faça alterações relacionadas ao mesmo objetivo.
3. Atualize testes e documentação afetados.
4. Execute `mvn test` e o teste manual proporcional ao risco.
5. Revise arquivos gerados antes do commit.
6. Abra um pull request explicando problema, solução e forma de validação.

Checklist sugerido para o pull request:

- [ ] A alteração tem escopo claro.
- [ ] Há teste de regressão ou justificativa para sua ausência.
- [ ] A suíte completa passa.
- [ ] Textos novos existem em português e inglês.
- [ ] A interface foi conferida nos estados relevantes.
- [ ] O README ou a documentação foram atualizados quando necessário.
- [ ] Nenhum cache, segredo, caminho pessoal ou artefato gerado foi incluído.

## Relatando problemas

Inclua informações suficientes para reprodução:

- versão da Orion e do plugin;
- sistema operacional e JDK;
- Maven, Gradle ou Java simples;
- estrutura mínima do projeto;
- passos exatos;
- resultado esperado e observado;
- logs ou captura de tela sem dados sensíveis.

Para falhas de IntelliSense, informe também se o status chegou a **Java: IntelliSense pronto**. Para
falhas de build ou teste, inclua a saída relevante e os itens apresentados no painel **Problemas**.
