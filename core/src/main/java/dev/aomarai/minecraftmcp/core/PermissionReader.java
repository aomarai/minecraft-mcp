package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/** Read-only view of who is an operator and who is whitelisted. */
public interface PermissionReader extends GamePort {

    CompletableFuture<Permissions> permissions();
}
