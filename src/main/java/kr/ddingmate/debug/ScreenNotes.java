package kr.ddingmate.debug;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * 서버 창(의뢰·시설 등) 모양 기록. 내가 연 상자형 창의 제목과 칸 이름·설명을 처음 볼 때 한 번씩
 * config/ddingmate-screens.txt 에 적는다. 서버 표기가 바뀌었을 때 모드를 맞추는 데 쓰는 내 컴퓨터 안의 기록이며
 * 어디로도 보내지 않는다. 파일이 1MB 를 넘으면 더 적지 않는다.
 */
public final class ScreenNotes {

    private static final boolean ENABLED = Boolean.getBoolean("ddingmate.screens");
    private static final long MAX_BYTES = 1_000_000;
    private static final Set<String> SEEN = new HashSet<>();
    private static Object lastScreen;

    private ScreenNotes() {}

    /** 10틱마다 */
    public static void tick(MinecraftClient client) {
        // 개발용 기록이라 배포판에서는 꺼 둔다. 켜려면 JVM 인수 -Dddingmate.screens=true
        if (!ENABLED) return;
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            lastScreen = null;
            return;
        }
        StringBuilder sb = new StringBuilder();
        String title = ItemKeys.stripGlyphs(screen.getTitle().getString()).trim();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            String line = describe(slot.id, slot.getStack());
            String sig = line.replaceAll("\\d", "#");
            if (!SEEN.add(sig)) continue;
            sb.append(line).append('\n');
        }
        boolean newScreen = lastScreen != screen;
        lastScreen = screen;
        if (sb.isEmpty() && !newScreen) return;
        if (sb.isEmpty()) return;
        write("== 창: " + title + " (" + java.time.LocalDateTime.now().withNano(0) + ")\n" + sb);
    }

    private static String describe(int slot, ItemStack s) {
        StringBuilder sb = new StringBuilder();
        sb.append("  [").append(slot).append("] ").append(ItemKeys.stripGlyphs(s.getName().getString()).trim());
        sb.append(" ×").append(s.getCount()).append(" <").append(Registries.ITEM.getId(s.getItem()).getPath());
        CustomModelDataComponent cmd = s.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd != null && !cmd.floats().isEmpty()) sb.append(" ").append(cmd.floats().get(0).intValue());
        var model = s.getComponentChanges().get(DataComponentTypes.ITEM_MODEL);
        if (model != null && model.isPresent()) sb.append(" ").append(model.get());
        sb.append(">");
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text l : lore.lines()) {
                String t = ItemKeys.stripGlyphs(l.getString()).trim();
                if (!t.isEmpty()) sb.append(" | ").append(t);
            }
        }
        return sb.toString();
    }

    private static void write(String text) {
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("ddingmate-screens.txt");
            if (Files.exists(p) && Files.size(p) > MAX_BYTES) return;
            Files.writeString(p, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            DdingMate.LOG.warn("창 기록을 남기지 못했어요: {}", e.toString());
        }
    }
}
