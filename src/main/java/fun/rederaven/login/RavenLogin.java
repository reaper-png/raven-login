package fun.rederaven.login;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod("ravenlogin")
public final class RavenLogin {
    public RavenLogin() {
        NeoForge.EVENT_BUS.register(new LoginEvents());
    }
}
