package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.RuntimeInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/** Describes the running Fabric server. Reads constants only, so it is safe off the main thread. */
final class FabricRuntimeInfo implements RuntimeInfo {

    private final MinecraftServer server;

    FabricRuntimeInfo(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String describe() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        return "Fabric " + loader + " / Minecraft " + server.getServerVersion() + " / server";
    }
}
