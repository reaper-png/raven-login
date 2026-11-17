# Raven Login V0.1.0 — NeoForge 1.21.1 (server-only)

**Status:** projeto-fonte para compilação e TESTES em um servidor de homologação. A compilação completa de NeoForge e a validação de segurança em jogo ainda não foram executadas neste ambiente.

## Instalação segura (ordem)

1. **Backup** de `raven_users` e do site.
2. No `phpMyAdmin`, selecione o banco `raven_users` e execute, **uma vez**, `site/sql/01_customer_minecraft_login.sql`.
3. Gere chave com `openssl rand -hex 32` no servidor. Nunca compartilhe a chave.
4. Adicione `RAVEN_LOGIN_API_TOKEN="<chave>"` ao `.env` **do site**. Certifique-se de que o `.env` não é acessível na internet.
5. Envie `site/public_html/api/raven-login.php` para `public_html/api/raven-login.php`, ao lado do `db/config.php` existente em `public_html/db/config.php` (o endpoint inclui este arquivo).
6. Teste HTTPS e autenticação usando o mesmo token, sem publicá-lo ou deixá-lo no histórico do shell. Ver exemplos abaixo.
7. Compile o mod com **Java 21**: `gradlew.bat build` (Windows). Se o wrapper não estiver no ZIP, copie `gradlew`, `gradlew.bat` e `gradle/wrapper` do MDK oficial NeoForge 1.21.1.
8. Copie **somente o JAR** para a pasta `mods` do servidor dedicado; **não no cliente**.
9. Inicie o servidor para gerar `config/ravenlogin-server.properties`; edite `api.url` e `api.token` com a chave idêntica à do site; reinicie o servidor ou use `/ravenlogin reload` pelo console.
10. **Requer servidor Minecraft com `online-mode=true`** e identidade confiável via proxy autenticado; nunca confie somente no nick em servidores offline/cracked.
11. Execute testes em contas descartáveis antes de liberar acesso aos jogadores.

## Fluxo do jogador

- Entra usando o nickname cadastrado em `customer_accounts.minecraft_nick`.
- É colocado temporariamente no modo espectador e mantido na posição inicial até autenticar.
- Digita `/login <senha-do-site>` e é autenticado pela API HTTPS. Nick é lido da sessão Minecraft; não existe `/register`.
- Falha 1 -> 2 tentativas restantes; falha 2 -> 1; falha 3 -> bloqueio permanente até o admin liberar.
- Falhas de HTTP/API **não consomem tentativas**. Acesso permanece negado.
- Jogador é obrigado a autenticar novamente após reconectar. Sessão não dispensa a senha.
- Conta inexistente, desativada ou bloqueada não pode jogar.
- A liberação de uma conta é independente do campo `is_active` do site.

## Administração

`/ravenlogin unlock <nick>` — OP nível 4 ou console; zera tentativas e bloqueio da conta no site.

`/ravenlogin reload` — OP nível 4 ou console; recarrega URL/token/timeout local.

O site pode ter no futuro um botão administrativo que chame a mesma lógica de liberação por servidor. A API `unlock` é acessível somente com o token de integração do Minecraft, nunca em uma página pública.

## SQL

Novas colunas em **customer_accounts**, sem mexer em `admin_users`:

- `minecraft_login_failed_attempts` — tentativas inválidas persistentes no MySQL.
- `minecraft_login_locked_at` — trava permanente a partir da 3ª tentativa.
- `minecraft_login_last_failure_at` — auditoria mínima.
- `minecraft_login_last_success_at` — último login no servidor.
- `minecraft_login_last_unlocked_at` — última liberação administrativa.

## Exemplo de teste de API (sem expor token no histórico)

No Linux (ou outro cliente CLI que tenha curl):

```bash
read -rsp 'Raven API token: ' RAVEN_TOKEN; echo
curl --silent --show-error --fail-with-body -X POST \
  'https://rederaven.fun/api/raven-login.php' \
  -H "X-Raven-Token: $RAVEN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"action":"status","nickname":"NickCadastrado"}'
unset RAVEN_TOKEN
```

Se a conta estiver ativa, retorna `{ "ok": true, "status": "ready" }`.

### Segurança importante

**`/login <senha>` transmite sua senha do site como comando do Minecraft.** Embora o mod não registre nem imprima a senha, outros mods, proxies ou sistemas de auditoria podem registrar comandos. Em produção, o ideal é evoluir para **códigos de login de uso único emitidos pelo site**. Esse método evita reutilizar a senha do site no chat/comando.

A segurança de "três erros bloqueiam a conta" implica risco de **negação de serviço contra terceiros** (alguém pode errar propositalmente a senha de um nick e travá-lo). Considere proteção anti-abuso no proxy, antirate-limit por IP e possibilidade de desbloqueio por um admin autenticado no site.

**Importante sobre isolamento pré-login:** esta versão usa modo espectador, congelamento de posição e cancelamento de comandos/interações comuns. **Isso não substitui uma auditoria completa de todos os pacotes de mods terceiros**. Teste o bloqueio de inventário, comandos, GUIs e outras interações do modpack. Para produção com alto risco, acrescente interceptação de pacotes e testes automatizados de bypass antes de expor o servidor.

**Não** exponha MySQL/3306 ou o token da API ao cliente. O `is_active` do site continua sendo respeitado.

## Arquivos

- `src/main/java/.../LoginEvents.java` — autenticação assíncrona, bloqueio de jogo, comandos.
- `src/main/java/.../LoginApi.java` — chamadas HTTPS (somente servidor).
- `src/main/java/.../LoginConfig.java` — configuração privada (somente servidor).
- `site/public_html/api/raven-login.php` — PHP de autenticação e bloqueio.
- `site/sql/01_customer_minecraft_login.sql` — migração do banco `raven_users`.
- `server/config/ravenlogin-server.properties.example` — exemplo de configuração.

**Compatibilidade:** NeoForge 1.21.1, Java 21, servidor dedicado. O mod não deve estar instalado no cliente.
