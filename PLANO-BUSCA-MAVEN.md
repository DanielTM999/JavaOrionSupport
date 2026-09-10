# Reduzir o custo da busca de dependências no Maven Central

## Contexto

O painel de dependências consulta o Maven Central a cada busca, e esse request vira e mexe dá timeout.
Hoje o custo é alto e o comportamento em falha é o pior possível:

- `MavenCentralClient` usa um único `TIMEOUT = 20s` **tanto para conectar quanto para responder**
  (`src/main/java/dtm/ide/deps/MavenCentralClient.java:24`). Toda busca com rede ruim trava 20s.
- **Falha nunca é memorizada.** O cache do coordenador só guarda sucesso
  (`DependencyManagerCoordinator.java:265-272`, `result.ifPresent(...)`), então cada tecla (debounce de
  350ms) e cada evento do watcher do repositório local paga o timeout inteiro **de novo**.
- A aba Atualizações dispara **uma requisição HTTP por dependência, em sequência**
  (`DependencyManagerCoordinator.java:138-152`), automaticamente ao clicar na aba
  (`DependencyManagerPanel.java:494`). Com 40 dependências e o Central fora, são 40 × 20s em série.

O índice local **não** é o gargalo: o repositório desta máquina (`D:\MavenRepository`) tem 3.429
diretórios e 3.921 artefatos, indexado em milissegundos. O custo é inteiramente a perna web.

**Resultado esperado:** a busca nunca fica cara. Quando o Central falha, o plugin para de insistir por
um período e responde na hora com o índice local, e o usuário pode limitar/desligar a perna web nas
configurações.

## Decisões

| Tema | Decisão |
|---|---|
| Comportamento em falha | Cool-off (circuit breaker): 1 falha fecha o portão por 60s |
| Timeouts | 5s para conectar / 10s para responder |
| Aba Atualizações | Paralelismo limitado (6 simultâneas), abortando no cool-off |
| Configurável | Sim: timeout + toggle "somente repositório local" |

## Implementação

### 1. `MavenCentralClient` — timeouts separados e cool-off

`src/main/java/dtm/ide/deps/MavenCentralClient.java`

- Trocar `TIMEOUT` único por `CONNECT_TIMEOUT = 5s` (em `HttpClient.connectTimeout`) e um
  `volatile Duration requestTimeout` (default 10s) aplicado em `HttpRequest.timeout(...)`.
  Expor `setRequestTimeout(Duration)` para as configurações empurrarem o valor em runtime — o cliente
  é criado uma vez só em `JavaIdeAdapter.java:6555`.
- **Circuit breaker no único ponto de estrangulamento, `get(String url)`** — assim search *e*
  `versions()` passam a respeitá-lo de graça:
  - campo `AtomicLong openUntil`;
  - no topo de `get`: se `clock() < openUntil`, retorna `null` **sem nenhum HTTP**, logando em `debug`
    (não `warn`, para não repetir o alerta a cada tecla);
  - em qualquer falha (não-200, exceção, payload malformado): `openUntil = agora + COOLDOWN (60s)`;
  - em sucesso: zera `openUntil`.
- Expor `boolean isCoolingDown()` — usado pelo coordenador para abortar cedo a aba Atualizações.
- Testabilidade: estender o construtor package-private que já existe
  (`MavenCentralClient(String searchUrl)`) para receber também `Duration cooldown` e um
  `LongSupplier clock`, com o construtor público delegando aos defaults. É o mesmo padrão de injeção
  que `OsvClient` já usa no seu construtor package-private.

Não mexer no `versionCache`: ele não tem TTL, mas isso *reduz* requisições e falha nunca é cacheada
(`versions()` retorna `List.of()` sem gravar). Mantém-se como está.

### 2. Estado "pulado" distinto de "falhou"

Hoje `SearchOutcome` só tem `remoteFailed`, então qualquer decisão de **não** chamar a web acabaria
mostrando "Maven Central indisponível" — mentira para o usuário.

Em `DependencyManagerPanel.SearchOutcome` (`src/main/java/dtm/ide/ui/DependencyManagerPanel.java:97`),
trocar o boolean `remoteFailed` por um enum `RemoteStatus { OK, FAILED, SKIPPED }`, mantendo
`localUnavailable` como está. `applySearchStatus` (`:594`) ganha o ramo de `SKIPPED` com um texto
neutro, sem tom de erro.

### 3. Coordenador — pular a web quando não vale o custo

`src/main/java/dtm/ide/DependencyManagerCoordinator.java`

- Em `remoteSearch(...)`, devolver `SKIPPED` sem tocar na rede quando:
  - o modo "somente repositório local" estiver ligado; **ou**
  - a consulta tiver menos de 3 caracteres e não contiver `:` (uma query de 1 caractere vira
    `a OR a*` em `toSolrQuery` — o pior caso possível para o Solr e inútil como resultado).
    O índice local continua respondendo normalmente a partir do 1º caractere.
- `latestVersions(...)` (`:138-152`): manter automático, mas trocar o `for` sequencial por execução
  com **paralelismo limitado a 6**, usando `PluginTaskExecutor` (que já implementa `Executor`) com um
  `Semaphore(6)`; cada tarefa checa `central.isCoolingDown()` na entrada e desiste, e o modo
  local-only retorna vazio de imediato.

### 4. Configurações

- `src/main/java/dtm/ide/settings/JavaPluginSettings.java` — duas chaves novas seguindo o padrão
  existente (`KEY_*` + campo + getter/setter + linha em `load()`, `save()` e `restoreDefaults()`),
  reaproveitando os helpers `integer(...)` e `bool(...)`:
  - `dependencySearchTimeoutSeconds` (int, default 10) — precedente: `defaultJdkVersion`;
  - `dependencySearchLocalOnly` (bool, default false) — precedente: `buildOffline`.
- `src/main/java/dtm/ide/settings/JavaSettingsPage.java` — nova seção via o helper
  `section(String, JComponent...)` (`:257`), com um `JCheckBox` e um campo numérico via
  `labeled(String, JComponent)` (`:284`); duas linhas em `onApply()` (`:127`). `onRestoreDefaults()`
  já recarrega tudo sozinho.
- `src/main/java/dtm/ide/JavaIdeAdapter.java` — em `applySettings()` (`:6929`), empurrar o timeout
  para o `MavenCentralClient` e o flag local-only para o coordenador.

### 5. i18n

Adicionar as chaves novas (rótulos da seção de configurações + o status de `SKIPPED`) em
`src/main/resources/languages/en-US.json` **e** `pt-BR.json`. Os dois arquivos têm hoje 829 chaves e
precisam continuar em paridade.

## Testes

- **Novo** `MavenCentralClientResilienceTest` — reaproveitar o harness de `HttpServer` local que já
  existe em `src/test/java/dtm/ide/deps/MavenCentralClientLoggingTest.java`:
  - depois de uma falha, a chamada seguinte **não** emite request nenhum (contador de hits do servidor
    não sobe) e retorna na hora;
  - passado o cool-off (via o clock injetado), volta a tentar;
  - um endpoint lento estoura dentro do orçamento configurado, não em 20s.
- `src/test/java/dtm/ide/DependencyManagerCoordinatorTest.java` (já existe, com `CountingCentralClient`
  e repositório local temporário): modo local-only não chama a web; consulta com menos de 3 caracteres
  não chama a web mas ainda traz resultado local; `latestVersions` aborta no cool-off.
- `src/test/java/dtm/ide/ui/DependencyManagerPanelTest.java`: `SKIPPED` mostra o status neutro, não o
  badge de falha.
- Atualizar as construções de `SearchOutcome` nesses dois testes para o enum novo.

## Verificação

```bash
mvn -o test
mvn -o package -Dmaven.antrun.skip=true
```

Suíte atual: 1136 testes verdes. Manualmente, com a rede desligada ou apontando para um endpoint
inválido: a 1ª busca mostra o badge parcial em ~10s e as seguintes voltam **instantâneas** só com o
índice local; a aba Atualizações não trava mais por dependência.

## Fora de escopo

- `OsvClient` (aba Saúde) também usa 20s, mas faz **uma** requisição em lote para todas as
  coordenadas — o custo já é aceitável e fica para depois.
- Unificar os 9 `Duration TIMEOUT` espalhados pelo `src/main/java` num lugar só.
- Batching das consultas da aba Atualizações numa única query Solr com cláusulas `OR`: é possível,
  mas o core default só devolve `latestVersion` (que pode ser pré-release), e hoje a aba precisa da
  última versão **estável** — mudaria a semântica. Por isso, paralelismo em vez de batching.
