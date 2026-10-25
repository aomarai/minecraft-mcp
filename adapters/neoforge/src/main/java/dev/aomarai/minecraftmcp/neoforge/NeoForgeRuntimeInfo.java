package dev.aomarai.minecraftmcp.neoforge;

import dev.aomarai.minecraftmcp.core.RuntimeInfo;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** Describes the running NeoForge server. Reads constants only, so it is safe off the main thread. */
final class NeoForgeRuntimeInfo implements RuntimeInfo {

    private final MinecraftServer server;

    NeoForgeRuntimeInfo(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String describe() {
        String loader = ModList.get().getModContainerById("neoforge")
                .map(mod -> mod.getModInfo().getVersion().toString())
                .orElse("unknown");
        return "NeoForge " + loader + " / Minecraft " + server.getServerVersion() + " / server";
    }
}
