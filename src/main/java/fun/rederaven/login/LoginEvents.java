package fun.rederaven.login;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Auth guard runs only on the logical server; no client classes or networking channel. */
public final class LoginEvents {
    private static final String SAVED_MODE = "ravenlogin_original_gamemode";
    private final Map<UUID, Session> sessions = new HashMap<>();
    private LoginConfig config = LoginConfig.load();
    private LoginApi api = new LoginApi(config);
    private long ticks;

    private static final class Session {
        final UUID id;
        final Vec3 anchor;
        final GameType mode;
        final long joinedAt;
        long lastPrompt;
        boolean inFlight;
        boolean authenticated;
        Session(ServerPlayer p, GameType mode, long now) {
            id = p.getUUID(); anchor = p.position(); this.mode = mode; joinedAt = now;
        }
    }

    private boolean blocked(ServerPlayer p) {
        Session s = sessions.get(p.getUUID());
        // Unknown session must be treated as unauthenticated until the server initializes it.
        return s == null || !s.authenticated;
    }

    private void tell(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal("§5[Raven Login] §r" + msg));
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        GameType original = p.gameMode.getGameModeForPlayer();
        // Recover the original mode if the server crashed during an unauthenticated session.
        if (p.getPersistentData().contains(SAVED_MODE)) {
            try { original = GameType.byName(p.getPersistentData().getString(SAVED_MODE)); }
            catch (Exception ignored) { original = GameType.SURVIVAL; }
        }
        Session session = new Session(p, original, ticks);
        sessions.put(p.getUUID(), session);
        p.getPersistentData().putString(SAVED_MODE, original.getName());
        p.setGameMode(GameType.SPECTATOR);
        p.setDeltaMovement(Vec3.ZERO);
        tell(p, "§eAutentique-se com §f/login <senha>§e. Você tem 3 tentativas (contadas no site).");
        tell(p, "§7Conta: §f" + p.getGameProfile().getName() + "§7. Cadastre sua senha em rederaven.fun.");
        checkAccount(p, session);
    }

    private void checkAccount(ServerPlayer p, Session session) {
        api.call("status", p.getGameProfile().getName(), "")
            .thenAccept(ans -> p.server.execute(() -> {
                if (sessions.get(p.getUUID()) != session || session.authenticated) return;
                switch (ans.status()) {
                    case "account_not_found" -> disconnect(p, "Conta não encontrada. Cadastre seu nick no site.");
                    case "account_locked" -> disconnect(p, "Conta bloqueada. Procure um administrador.");
                    case "account_disabled" -> disconnect(p, "Conta desativada. Procure um administrador.");
                    case "ready" -> tell(p, "§aConta localizada. Digite /login <senha>.");
                    default -> tell(p, "§cServiço de login indisponível. O acesso ficará bloqueado até normalizar.");
                }
            }));
    }

    private void disconnect(ServerPlayer p, String reason) {
        if (p.connection != null) p.connection.disconnect(Component.literal("Raven Login: " + reason));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Session s = sessions.remove(p.getUUID());
        if (s != null && !s.authenticated) restoreMode(p, s);
    }

    private void restoreMode(ServerPlayer p, Session s) {
        p.setGameMode(s.mode);
        p.getPersistentData().remove(SAVED_MODE);
    }

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        ++ticks;
        MinecraftServer server = event.getServer();
        for (Session s : sessions.values()) {
            if (s.authenticated) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(s.id);
            if (p == null) continue;
            // Spectator + server-side re-anchoring stop normal gameplay until auth succeeds.
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            if (p.position().distanceToSqr(s.anchor) > 0.04) {
                p.teleportTo(s.anchor.x, s.anchor.y, s.anchor.z);
                p.setDeltaMovement(Vec3.ZERO);
            }
            if (ticks - s.lastPrompt >= 200) {
                s.lastPrompt = ticks;
                p.displayClientMessage(Component.literal("§d[Raven] §eUse §f/login <senha>§e para acessar o servidor."), true);
            }
            if (ticks - s.joinedAt > (long) config.loginTimeoutSeconds * 20) {
                disconnect(p, "Tempo de autenticação esgotado.");
            }
        }
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    login(p, StringArgumentType.getString(ctx, "password"));
                    return 1;
                })));
        event.getDispatcher().register(Commands.literal("ravenlogin")
            .requires(source -> source.hasPermission(4))
            .then(Commands.literal("unlock")
                .then(Commands.argument("nickname", StringArgumentType.word())
                    .executes(ctx -> {
                        String nick = StringArgumentType.getString(ctx, "nickname");
                        var source = ctx.getSource();
                        api.call("unlock", nick, "").thenAccept(ans -> source.getServer().execute(() -> {
                            source.sendSuccess(() -> Component.literal(ans.ok()
                                ? "Raven Login: conta desbloqueada: " + nick
                                : "Raven Login: falhou: " + ans.status()), false);
                        }));
                        return 1;
                    })))
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    config = LoginConfig.load(); api = new LoginApi(config);
                    ctx.getSource().sendSuccess(() -> Component.literal("Raven Login: config recarregada."), false);
                    return 1;
                })));
    }

    private void login(ServerPlayer p, String password) {
        Session s = sessions.get(p.getUUID());
        if (s == null) { tell(p, "§cSessão não encontrada. Reconecte."); return; }
        if (s.authenticated) { tell(p, "§aVocê já está autenticado."); return; }
        if (s.inFlight) { tell(p, "§eAguarde a verificação anterior."); return; }
        if (password.isBlank() || password.length() > 256) { tell(p, "§cSenha inválida."); return; }
        s.inFlight = true;
        // Never log or echo the password; transport via HTTPS with timeouts.
        api.call("authenticate", p.getGameProfile().getName(), password)
            .thenAccept(ans -> p.server.execute(() -> {
                if (sessions.get(p.getUUID()) != s) return;
                s.inFlight = false;
                if (ans.ok() && "authenticated".equals(ans.status())) {
                    s.authenticated = true;
                    restoreMode(p, s);
                    tell(p, "§aAutenticado! Bem-vindo à Rede Raven.");
                    return;
                }
                switch (ans.status()) {
                    case "wrong_password" -> tell(p, "§cSenha incorreta. Tentativas restantes: " + ans.attemptsRemaining());
                    case "account_locked" -> disconnect(p, "Conta bloqueada após 3 tentativas. Solicite liberação a um administrador.");
                    case "account_not_found" -> disconnect(p, "Conta não encontrada no site.");
                    case "account_disabled" -> disconnect(p, "Conta desativada.");
                    default -> tell(p, "§cNão foi possível verificar o login. Tente novamente mais tarde.");
                }
            }));
    }

    @SubscribeEvent
    public void onCommands(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer p) || !blocked(p)) return;
        String raw = event.getParseResults().getReader().getString().trim();
        // Allow only /login; other commands are denied while unauthenticated.
        if (raw.equals("login") || raw.startsWith("login ")) return;
        event.setCanceled(true);
        tell(p, "§cVocê precisa se autenticar primeiro.");
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (blocked(event.getPlayer())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer p && blocked(p)) event.setCanPickup(TriState.FALSE);
    }
    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onUseEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && blocked(p)) event.setCanceled(true);
    }
}
