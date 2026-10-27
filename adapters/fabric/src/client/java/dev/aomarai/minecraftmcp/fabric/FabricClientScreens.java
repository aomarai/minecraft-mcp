package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.ScreenControl;
import dev.aomarai.minecraftmcp.core.ScreenElement;
import dev.aomarai.minecraftmcp.core.ScreenElement.Role;
import dev.aomarai.minecraftmcp.core.ScreenReader;
import dev.aomarai.minecraftmcp.core.ScreenSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Looks at the open vanilla screen and acts on it the way the player's mouse and keyboard would. Everything runs on
 * the client's main thread. The elements are the visible widgets of the screen (buttons, text fields, sliders,
 * lists, labels), found by walking its children; a mod that builds its screen from ordinary widgets shows up the same
 * way. Item slots of container screens are listed as `slot` elements, labeled with the registry id and count.
 */
final class FabricClientScreens implements ScreenReader, ScreenControl {

    /** How deep the walk follows widgets that hold other widgets. */
    private static final int MAX_DEPTH = 4;

    private static final int SLOT_SIZE = 16;

    /** A listed element with the point in the middle of it, where a click or scroll lands. */
    private record Node(ScreenElement element, double centerX, double centerY) {
    }

    @Override
    public CompletableFuture<ScreenSnapshot> snapshot() {
        return onClientThread(minecraft -> {
            Screen screen = ClientCompat.screen(minecraft);
            if (screen == null) {
                return new ScreenSnapshot("", "", overlay(minecraft), List.of());
            }
            List<ScreenElement> elements = nodes(screen).stream().map(Node::element).toList();
            return new ScreenSnapshot(screen.getClass().getSimpleName(), screen.getTitle().getString(),
                    overlay(minecraft), elements);
        });
    }

    @Override
    public CompletableFuture<String> click(String element) {
        return onClientThread(minecraft -> {
            Screen screen = openScreen(minecraft);
            Node node = find(screen, element);
            if (!node.element().enabled()) {
                throw new IllegalStateException(element + " is disabled");
            }
            if (!ClientCompat.click(screen, node.centerX(), node.centerY())) {
                throw new IllegalStateException("the screen did not take the click on " + describe(node.element())
                        + "; something else may be covering it");
            }
            return "clicked " + describe(node.element());
        });
    }

    @Override
    public CompletableFuture<String> type(String text) {
        return onClientThread(minecraft -> {
            Screen screen = openScreen(minecraft);
            if (!(screen.getFocused() instanceof EditBox)) {
                throw new IllegalStateException("no text field is focused; click one first");
            }
            text.codePoints().forEach(codePoint -> ClientCompat.type(screen, codePoint));
            return "typed " + text.codePointCount(0, text.length()) + " characters";
        });
    }

    @Override
    public CompletableFuture<String> scroll(String element, int notches) {
        return onClientThread(minecraft -> {
            Screen screen = openScreen(minecraft);
            if (element.isEmpty()) {
                screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0.0, notches);
                return "scrolled " + notches + " over the middle of the screen";
            }
            Node node = find(screen, element);
            screen.mouseScrolled(node.centerX(), node.centerY(), 0.0, notches);
            return "scrolled " + notches + " over " + describe(node.element());
        });
    }

    @Override
    public CompletableFuture<String> openInventory() {
        return onClientThread(minecraft -> {
            if (ClientCompat.overlay(minecraft) != null) {
                throw new IllegalStateException("the game is showing " + overlay(minecraft) + "; try again in a moment");
            }
            if (minecraft.player == null) {
                throw new IllegalStateException("the player is not in a world");
            }
            ClientCompat.open(minecraft, new InventoryScreen(minecraft.player));
            return "opened the inventory";
        });
    }

    /** The screen the player has open, or an error: input is never sent to the world or while a loading overlay is up. */
    private static Screen openScreen(Minecraft minecraft) {
        if (ClientCompat.overlay(minecraft) != null) {
            throw new IllegalStateException("the game is showing " + overlay(minecraft) + "; try again in a moment");
        }
        Screen screen = ClientCompat.screen(minecraft);
        if (screen == null) {
            throw new IllegalStateException("no screen is open");
        }
        return screen;
    }

    private static String overlay(Minecraft minecraft) {
        Object overlay = ClientCompat.overlay(minecraft);
        return overlay == null ? "" : overlay.getClass().getSimpleName();
    }

    private Node find(Screen screen, String id) {
        List<Node> nodes = nodes(screen);
        return nodes.get(ScreenElement.resolve(nodes.stream().map(Node::element).toList(), id));
    }

    private static String describe(ScreenElement element) {
        return element.role().json() + " '" + element.label() + "'";
    }

    /** Counts the screens seen, so an id listed for one screen is not honoured on the next. Client thread only. */
    private WeakReference<Screen> lastScreen = new WeakReference<>(null);
    private long generation;

    private long generationOf(Screen screen) {
        if (lastScreen.get() != screen) {
            lastScreen = new WeakReference<>(screen);
            generation++;
        }
        return generation;
    }

    private List<Node> nodes(Screen screen) {
        long generation = generationOf(screen);
        List<Node> nodes = new ArrayList<>();
        collect(screen.children(), nodes, 0, screen.getFocused(), generation);
        if (screen instanceof AbstractContainerScreen<?> container) {
            for (Slot slot : container.getMenu().slots) {
                if (slot.isActive() && nodes.size() < ScreenSnapshot.MAX_ELEMENTS) {
                    nodes.add(slotNode(nodes.size(), generation, container, slot));
                }
            }
        }
        return nodes;
    }

    /** Slots are 16 by 16 and positioned relative to the container's own corner, which the access widener opens. */
    private static Node slotNode(int index, long generation, AbstractContainerScreen<?> container, Slot slot) {
        int x = container.leftPos + slot.x;
        int y = container.topPos + slot.y;
        String label = stackLabel(slot.getItem());
        ScreenElement element = new ScreenElement(ScreenElement.idOf(index, Role.SLOT, generation, label), Role.SLOT, label,
                x, y, SLOT_SIZE, SLOT_SIZE, true, false);
        return new Node(element, x + SLOT_SIZE / 2.0, y + SLOT_SIZE / 2.0);
    }

    /** The registry id and count, never the item's name or lore: players control those. */
    private static String stackLabel(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    private static void collect(List<? extends GuiEventListener> listeners, List<Node> nodes, int depth,
                                GuiEventListener focused, long generation) {
        for (GuiEventListener listener : listeners) {
            if (listener instanceof AbstractWidget widget) {
                if (widget.visible && nodes.size() < ScreenSnapshot.MAX_ELEMENTS) {
                    nodes.add(node(nodes.size(), generation, widget, widget == focused));
                }
            } else if (listener instanceof ContainerEventHandler container && depth < MAX_DEPTH) {
                collect(container.children(), nodes, depth + 1, focused, generation);
            }
        }
    }

    private static Node node(int index, long generation, AbstractWidget widget, boolean focused) {
        Role role = role(widget);
        String label = widget.getMessage().getString();
        ScreenElement element = new ScreenElement(ScreenElement.idOf(index, role, generation, label), role, label,
                widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), widget.active, focused);
        return new Node(element, widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0);
    }

    private static Role role(AbstractWidget widget) {
        if (widget instanceof EditBox) {
            return Role.TEXT_FIELD;
        }
        if (widget instanceof Checkbox) {
            return Role.CHECKBOX;
        }
        if (widget instanceof AbstractSliderButton) {
            return Role.SLIDER;
        }
        if (widget instanceof AbstractButton) {
            return Role.BUTTON;
        }
        if (widget instanceof AbstractSelectionList<?>) {
            return Role.LIST;
        }
        return widget instanceof AbstractStringWidget ? Role.LABEL : Role.OTHER;
    }

    /** Runs on the client's main thread and answers with the result; a call that already timed out is skipped. */
    private static <T> CompletableFuture<T> onClientThread(Function<Minecraft, T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(action.apply(minecraft));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
