import sys

path = 'src/main/java/com/audiophilecraft/sound/AudioEngine.java'
with open(path, 'r', encoding='utf-8') as f:
    c = f.read()

target = '''            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                return acousticScanner.scanVenue(world, probePos, stageDir);
            }).thenAcceptAsync(preset -> {'''

replacement = '''            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return acousticScanner.scanVenue(world, probePos, stageDir);
                } catch (Exception e) {
                    System.err.println("Venue scan crash: " + e.getMessage());
                    return null;
                }
            }).exceptionally(ex -> {
                System.err.println("Venue scan future failed: " + ex.getMessage());
                return null;
            }).thenAcceptAsync(preset -> {'''

if target in c:
    c = c.replace(target, replacement)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(c)
    print("Replaced successfully")
else:
    print("Target not found")
